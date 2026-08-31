import React from 'react';
import { ArrowRight, AlertTriangle, AlertCircle, MinusCircle } from 'lucide-react';
import { cn } from '../../utils';
import { Button } from '../ui';
import { AttentionItem, AttentionAction, AttentionSeverity } from '../../types/cockpit';

// ============================================================================
// Cockpit — single attention row.
//
// Severity pill (left, fixed width) + headline + detail line + time-pressure
// chip (right, muted) + primary action button. The whole row is clickable
// (opens the drawer) — only the action button stops propagation so a single
// click takes the obvious action without first opening the drawer.
//
// Critical rows promote the primary action from `outline` to `primary` so
// the eye lands on it before reading the headline.
//
// On narrow viewports, the time-pressure chip + action drop beneath the
// headline (handled by the parent container; this row uses `flex-wrap`).
// ============================================================================

interface AttentionRowProps {
  item: AttentionItem;
  onOpen: (item: AttentionItem) => void;
  onAction: (item: AttentionItem, action: AttentionAction) => void;
}

const SEVERITY_TONES: Record<AttentionSeverity, { bg: string; text: string; icon: React.ReactNode; label: string }> = {
  critical: {
    bg: 'bg-error-100 text-error-800 dark:bg-error-500/20 dark:text-error-200',
    text: 'text-error-700 dark:text-error-300',
    icon: <AlertCircle className="w-3 h-3" />,
    label: 'Critical',
  },
  high: {
    bg: 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-200',
    text: 'text-warning-700 dark:text-warning-300',
    icon: <AlertTriangle className="w-3 h-3" />,
    label: 'High',
  },
  medium: {
    bg: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800/60 dark:text-neutral-200',
    text: 'text-neutral-600 dark:text-neutral-300',
    icon: <MinusCircle className="w-3 h-3" />,
    label: 'Medium',
  },
};

export const AttentionRow: React.FC<AttentionRowProps> = ({ item, onOpen, onAction }) => {
  const tone = SEVERITY_TONES[item.severity];
  const primary = item.actions[0];
  const isCritical = item.severity === 'critical';
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onOpen(item)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(item); } }}
      className={cn(
        'flex flex-wrap items-center gap-3 p-3 rounded-lg border border-neutral-200 dark:border-primary-800 transition-colors cursor-pointer',
        'bg-white dark:bg-primary-900',
        'hover:bg-neutral-50 dark:hover:bg-primary-800/40',
        'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
      )}
    >
      <span className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium shrink-0 w-[88px] justify-center',
        tone.bg,
      )}>
        {tone.icon} {tone.label}
      </span>

      <div className="min-w-0 flex-1 basis-full md:basis-auto">
        <p className="text-sm text-primary-900 dark:text-neutral-50 truncate" style={{ fontWeight: 500 }}>
          {item.headline}
        </p>
        <p className="body-sm text-neutral-500 dark:text-neutral-400 truncate">
          {item.detail}
        </p>
      </div>

      {/* Time-pressure chip + action stack beneath the headline at <md, then
          inline at the right at md+. The `basis-full md:basis-auto` on the
          headline above is what lets the row wrap cleanly. */}
      <span className={cn('body-sm shrink-0', tone.text)}>
        {item.timePressure.displayText}
      </span>

      {primary && (
        <Button
          variant={isCritical ? 'primary' : 'outline'}
          size="sm"
          onClick={(e) => { e.stopPropagation(); onAction(item, primary); }}
          rightIcon={<ArrowRight className="w-4 h-4" />}
        >
          {primary.label}
        </Button>
      )}
    </div>
  );
};
