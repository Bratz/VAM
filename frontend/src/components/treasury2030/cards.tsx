import React from 'react';
import { FlaskConical } from 'lucide-react';
import { Card } from '../ui';
import { cn } from '../../utils';

// ============================================================================
// Treasury 2030 — shared card chrome.
//
// The wireframe's own convention: dashed border = data placeholder (no real
// source yet), solid border = real chrome. The user chose the "Hybrid"
// handoff: regions backed by a real Aperture API render solid and live;
// regions that would depend on a forecast / AI / scenario model that does
// not exist yet render with an explicit ILLUSTRATIVE marker + dashed border
// so a treasurer can never mistake sample numbers for live figures.
// ============================================================================

/** Small "illustrative — not live data" chip. Warning tone, eyebrow recipe. */
export const IllustrativeBadge: React.FC<{ className?: string }> = ({ className }) => (
  <span
    className={cn(
      'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
      'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300',
      'uppercase tracking-[0.08em]',
      className,
    )}
    title="Illustrative — depends on a forecast / AI / scenario model not yet wired. Sample data."
  >
    <FlaskConical className="w-3 h-3" aria-hidden />
    Illustrative
  </span>
);

export interface SectionCardProps {
  /** The question this card answers (wireframe leads every card with one). */
  question: React.ReactNode;
  /** Uppercase sub-eyebrow under the question. */
  sub?: React.ReactNode;
  /** Footer left content (e.g. a derived takeaway). */
  footLeft?: React.ReactNode;
  /** Footer right content (e.g. a drill link label). */
  footRight?: React.ReactNode;
  /**
   * When true, the card is sample/illustrative: dashed border + badge.
   * When false/omitted, it's a live, real-data card (solid border).
   */
  illustrative?: boolean;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
}

/**
 * Question-led card matching the wireframe's `.card / .card-head / .card-foot`
 * structure, expressed in the Aperture design system (Card primitive,
 * semantic tokens, dark mode, typography utilities).
 */
export const SectionCard: React.FC<SectionCardProps> = ({
  question,
  sub,
  footLeft,
  footRight,
  illustrative,
  className,
  bodyClassName,
  children,
}) => (
  <Card
    padding="none"
    className={cn(
      'flex flex-col overflow-hidden',
      illustrative && 'border-dashed border-neutral-300 dark:border-primary-700',
      className,
    )}
  >
    <div className="flex items-start justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
      <div className="min-w-0">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 leading-snug">
          {question}
        </p>
        {sub && <p className="label mt-1">{sub}</p>}
      </div>
      {illustrative && <IllustrativeBadge className="shrink-0" />}
    </div>

    <div className={cn('flex-1 p-4', bodyClassName)}>{children}</div>

    {(footLeft || footRight) && (
      <div className="flex items-center justify-between gap-2 px-4 py-2 border-t border-neutral-100 dark:border-primary-800/60 text-xs text-neutral-500 dark:text-neutral-400">
        <span className="min-w-0 truncate">{footLeft}</span>
        {footRight && (
          <span className="shrink-0 text-primary-600 dark:text-accent-400">{footRight}</span>
        )}
      </div>
    )}
  </Card>
);
