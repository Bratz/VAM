import React from 'react';
import { ArrowRight } from 'lucide-react';
import { featureFlags } from '../../utils/featureFlags';
import { telemetry } from '../../utils/telemetry';
import Treasury2030DashboardPage from '../../pages/Treasury2030DashboardPage';
import DashboardClassicPage from '../../pages/DashboardClassicPage';

// ============================================================================
// Cockpit feature-flag gate.
//
// Resolves the `cockpit.v1` flag on every render and routes `/dashboard` to
// the new Treasury 2030 dashboard (flag on, default) or the classic
// dashboard (flag off). The small accent banner this wrapper renders above
// the classic page (when the flag is off) flips the flag back on. The flag
// key is retained for state continuity; it now means "Treasury 2030 vs
// Classic" (the operational Cockpit page is no longer routed here).
//
// Why the banner lives here and not on the classic page itself: the
// cockpit-rollout spec said the classic dashboard stays untouched as the
// 60-day fallback. The flag-gate wrapper is the natural place to own the
// rollout chrome — the classic page renders its body unchanged below.
//
// V2 replaces the synchronous localStorage check with an async config
// lookup (likely a React Query subscription). The wrapper signature stays
// the same — the cockpit and classic both already accept `{ onNavigate }`.
// ============================================================================

interface CockpitFeatureFlagProps {
  onNavigate: (page: string) => void;
}

export const CockpitFeatureFlag: React.FC<CockpitFeatureFlagProps> = ({ onNavigate }) => {
  // Read on every render so the toggle takes effect on the next paint.
  // The flag is rarely read so the localStorage hit is negligible.
  const cockpitOn = featureFlags.isOn('cockpit.v1');

  if (cockpitOn) {
    // The default /dashboard is the Treasury 2030 v2 "Cash Position" cockpit
    // (replaced the v1 performance-attribution composition). The `cockpit.v1`
    // flag key is kept so existing per-browser toggles still resolve; it now
    // gates "Treasury 2030 vs Classic". `onNavigate` is threaded through so
    // the cockpit's deep-links / quick-actions can route into existing pages.
    return <Treasury2030DashboardPage onNavigate={onNavigate} />;
  }

  // Flag is off → classic dashboard, but with a rollout-recovery banner on
  // top so pilot users who flipped to classic still have a one-click path
  // back to the new dashboard.
  const switchToCockpit = () => {
    telemetry.emit('cockpit.refresh.clicked');
    featureFlags.set('cockpit.v1', 'on');
    // Reload so the wrapper re-evaluates the flag and routes to CockpitPage
    // on the next mount. This avoids a stale-render edge case where the
    // localStorage write hasn't been observed by the synchronous check.
    window.location.reload();
  };

  return (
    <>
      {/* Accent-toned, 40px-tall banner. Sits above the classic dashboard's
          own content; the classic page renders normally below it. */}
      <div className="flex items-center gap-3 px-4 py-2 border-b border-accent-200 bg-accent-50 dark:border-accent-500/30 dark:bg-accent-500/10">
        <span className="body-sm text-neutral-700 dark:text-neutral-200">
          The new Treasury 2030 dashboard is available.
        </span>
        <button
          type="button"
          onClick={switchToCockpit}
          className="ml-auto inline-flex items-center gap-1 body-sm font-medium text-accent-700 hover:text-accent-800 dark:text-accent-300 dark:hover:text-accent-200 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-400 rounded"
        >
          Try the new dashboard
          <ArrowRight className="w-4 h-4" />
        </button>
      </div>
      <DashboardClassicPage onNavigate={onNavigate} />
    </>
  );
};
