import React, { useEffect, useState } from 'react';
import { RefreshCw, Clock, Globe } from 'lucide-react';
import { cn } from '../../utils';
import { Button } from '../ui';
import { SelectorBar, CockpitSelectorBarProps } from './SelectorBar';

// ============================================================================
// Cockpit — greeting strip.
//
// Top-of-page contextual band: who's looking, where in the world they are,
// when the data was last refreshed. The greeting text gets the page-title
// treatment (Fraunces serif via `.page-title`) — the rest is supporting
// chrome.
//
// The "balances refreshed N min ago" line shifts colour as the data ages:
// neutral up to 15 minutes, warning up to 60 minutes, error beyond. The
// shift is the only ambient signal that triage might be working from a
// stale picture.
// ============================================================================

interface GreetingStripProps extends CockpitSelectorBarProps {
  userName?: string;
  /** ISO timestamp of the last successful balances refresh. */
  lastRefreshedAt?: string;
  /** True while a refresh is in flight (spinner on the Refresh button). */
  refreshing?: boolean;
  onRefresh: () => void;
  /**
   * Optional callback to switch back to the classic dashboard. Renders a
   * small muted link in the strip — for pilot users during the 60-day
   * rollout overlap. Hidden when undefined.
   */
  onSwitchToClassic?: () => void;
}

const GREETINGS_BY_HOUR: Array<{ from: number; to: number; text: string }> = [
  { from: 5,  to: 12, text: 'Good morning' },
  { from: 12, to: 17, text: 'Good afternoon' },
  { from: 17, to: 22, text: 'Good evening' },
  { from: 22, to: 29, text: 'Working late' }, // wraps to 5am
];

function greetingFor(hour: number): string {
  for (const g of GREETINGS_BY_HOUR) {
    const adjustedHour = hour < 5 ? hour + 24 : hour;
    if (adjustedHour >= g.from && adjustedHour < g.to) return g.text;
  }
  return 'Hello';
}

// Asia covers 0..6 + 22..23 UTC ish; EMEA 6..14 UTC; Americas 14..22 UTC.
// We use the user's local hour as a proxy — same shift the real markets
// roll through.
function marketSessionFor(hour: number): { label: string; tone: 'info' | 'success' | 'warning' } {
  if (hour < 8 || hour >= 22) return { label: 'Asia session', tone: 'info' };
  if (hour < 14) return { label: 'EMEA session', tone: 'success' };
  return { label: 'Americas session', tone: 'warning' };
}

function freshnessClass(ageMin: number): string {
  if (ageMin >= 60) return 'text-error-600 dark:text-error-300';
  if (ageMin >= 15) return 'text-warning-600 dark:text-warning-300';
  return 'text-neutral-500 dark:text-neutral-400';
}

function freshnessLabel(ageMin: number): string {
  if (ageMin < 1) return 'just now';
  if (ageMin < 60) return `${Math.floor(ageMin)} min ago`;
  const hours = Math.floor(ageMin / 60);
  return `${hours}h ${Math.floor(ageMin - hours * 60)}m ago`;
}

export const GreetingStrip: React.FC<GreetingStripProps> = ({
  userName,
  lastRefreshedAt,
  refreshing,
  onRefresh,
  onSwitchToClassic,
  ...selectorProps
}) => {
  // Tick the clock + freshness indicator every 30s so the colour shift
  // happens without a full page refresh.
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30 * 1000);
    return () => clearInterval(id);
  }, []);

  const hour = now.getHours();
  const greeting = greetingFor(hour);
  const session = marketSessionFor(hour);
  const ageMin = lastRefreshedAt ? (now.getTime() - new Date(lastRefreshedAt).getTime()) / 60000 : 0;
  const localTime = now.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0 flex-1">
        <h1 className="page-title">
          {greeting}{userName ? `, ${userName.split(' ')[0]}` : ''}.
        </h1>
        <div className="flex items-center gap-3 flex-wrap mt-2 body-sm text-neutral-500 dark:text-neutral-400">
          <span className="inline-flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5" />
            {localTime} local
          </span>
          <span className="inline-flex items-center gap-1.5">
            <Globe className={cn(
              'w-3.5 h-3.5',
              session.tone === 'success' && 'text-success-600 dark:text-success-300',
              session.tone === 'warning' && 'text-warning-600 dark:text-warning-300',
              session.tone === 'info'    && 'text-info-600    dark:text-info-300',
            )} />
            {session.label}
          </span>
          {lastRefreshedAt && (
            <span className={freshnessClass(ageMin)}>
              Balances refreshed {freshnessLabel(ageMin)}
            </span>
          )}
        </div>
      </div>
      <div className="flex items-end gap-3 flex-wrap">
        <SelectorBar {...selectorProps} />
        <Button
          variant="outline"
          size="sm"
          onClick={onRefresh}
          disabled={refreshing}
          leftIcon={<RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />}
        >
          Refresh
        </Button>
        {onSwitchToClassic && (
          <button
            type="button"
            onClick={onSwitchToClassic}
            className="body-sm text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200 underline-offset-2 hover:underline"
          >
            Switch to classic view
          </button>
        )}
      </div>
    </div>
  );
};
