// ============================================================================
// Minimal feature-flag helper.
//
// The codebase doesn't have a global config/feature-flag service today, so
// flags use the lightest-possible mechanism: a
// localStorage key per flag, read on every check. V2 (when an app-wide
// config service lands) replaces the storage reads with a `useConfig(flag)`
// hook — the helper signature stays the same.
//
// Default values are baked in here; a flag with no entry reads as 'off'.
// ============================================================================

export type FlagValue = 'on' | 'off';

const DEFAULTS: Record<string, FlagValue> = {
  // (Simulator flags discontinued 2026-05-16 — the Cash-Concentration +
  // Notional-Pool simulator is now a first-class feature, no longer
  // flag-gated. All gates removed from App/navigation/SimulatorPage and the
  // dependent components.)
};

function storageKey(flag: string): string {
  return `featureFlag:${flag}`;
}

export const featureFlags = {
  /**
   * Read a flag. Returns the localStorage override if set, otherwise the
   * default. Unknown flags return `'off'` so callers can default-deny.
   */
  read(flag: string): FlagValue {
    try {
      const raw = localStorage.getItem(storageKey(flag));
      if (raw === 'on' || raw === 'off') return raw;
    } catch {
      // localStorage disabled — fall through to defaults.
    }
    return DEFAULTS[flag] ?? 'off';
  },

  /** Convenience boolean shorthand. */
  isOn(flag: string): boolean {
    return this.read(flag) === 'on';
  },

  /** Write an override. Pass null to clear. */
  set(flag: string, value: FlagValue | null): void {
    try {
      if (value === null) localStorage.removeItem(storageKey(flag));
      else localStorage.setItem(storageKey(flag), value);
    } catch {
      // localStorage disabled — silently degrade.
    }
  },
};
