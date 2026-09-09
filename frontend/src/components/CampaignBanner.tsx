import React from 'react';
import { X } from 'lucide-react';
import { Button, Badge } from './ui';
import { cn } from '../utils';
import type { Campaign } from '../hooks/useEligibleCampaign';

export interface CampaignBannerProps {
  campaign: Campaign;
  onDismiss: () => void;
  /**
   * Handles `campaign.ctaHref` — this app navigates via an internal
   * `onNavigate(pageKey)` callback, not real URL routing, so the caller
   * (which has that callback) resolves the href rather than this component
   * rendering a raw `<a>`.
   */
  onCtaClick: () => void;
  variant?: Campaign['variant'];
}

/**
 * Single dismissible cross-sell banner slot. Rendered by the caller only
 * when `useEligibleCampaign()` returns a campaign — there is no internal
 * "render nothing" branch here, so there's nothing to collapse when there
 * isn't one.
 */
export const CampaignBanner: React.FC<CampaignBannerProps> = ({
  campaign,
  onDismiss,
  onCtaClick,
  variant = campaign.variant,
}) => {
  const dismissButton = (
    <button
      type="button"
      onClick={onDismiss}
      aria-label="Dismiss offer"
      className={cn(
        'shrink-0 transition-colors',
        // primary sits on banner-slate (primary-800, #4c5c68) — neutral-300
        // (#c5c3c6) reads at only 3.95:1 there (fails 4.5:1), so this uses
        // neutral-200 (#dcdcdd, 5.04:1) instead, not a white/NN opacity
        // blend (never contrast-checked) or neutral-300.
        variant === 'primary' ? 'text-neutral-200 hover:text-white' : 'text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200'
      )}
    >
      <X className="w-4 h-4" />
    </button>
  );

  if (variant === 'quiet') {
    return (
      <div
        className={cn(
          'flex flex-col lg:flex-row lg:items-center lg:h-11 gap-2 lg:gap-3',
          'rounded-lg border border-neutral-200 bg-neutral-50 px-3 py-2 lg:py-0',
          'dark:border-primary-700 dark:bg-primary-800/40'
        )}
      >
        <div className="flex items-center gap-2 flex-wrap min-w-0">
          <Badge variant="accent" size="xs">{campaign.tag}</Badge>
          <span className="text-xs text-primary-900 dark:text-neutral-100">{campaign.headline}</span>
          <button
            type="button"
            onClick={onCtaClick}
            className="text-xs font-semibold text-accent-700 hover:underline dark:text-accent-400 shrink-0"
          >
            {campaign.ctaLabel}
          </button>
        </div>
        <div className="lg:ml-auto">{dismissButton}</div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex flex-col lg:flex-row lg:items-center lg:justify-between lg:h-[66px] gap-3',
        // bg-primary-800 = banner slate (#4c5c68) per the palette spec.
        'rounded-xl bg-primary-800 text-white px-4 py-3 lg:py-0'
      )}
    >
      <div className="flex items-start gap-3 min-w-0">
        <Badge variant="accent" size="sm" className="mt-0.5 shrink-0">{campaign.tag}</Badge>
        <div className="min-w-0">
          <p className="text-sm font-semibold leading-snug">{campaign.headline}</p>
          {/* text-neutral-200, not text-white/70 — see the dismissButton
              comment above for the contrast reasoning. */}
          <p className="text-[13px] text-neutral-200 leading-snug mt-0.5">{campaign.body}</p>
        </div>
      </div>
      <div className="flex items-center gap-3 shrink-0">
        <Button variant="accent" size="sm" fullWidth className="lg:w-auto" onClick={onCtaClick}>
          {campaign.ctaLabel}
        </Button>
        <button
          type="button"
          onClick={onDismiss}
          className="text-sm text-neutral-200 hover:text-white transition-colors shrink-0 whitespace-nowrap"
        >
          Not now
        </button>
        {dismissButton}
      </div>
    </div>
  );
};
