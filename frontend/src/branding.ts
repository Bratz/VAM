/**
 * Aperture — product brand constants.
 *
 * Single source of truth for the user-facing product name, tagline, and
 * supporting copy. Components / pages / docs that need to render the brand
 * should import from here rather than hardcoding strings.
 *
 * <p>Note on naming: <b>Aperture</b> is the customer-facing product brand.
 * <b>VAM</b> remains the internal codename — visible in the Java package
 * ({@code com.bank.vam}), the Maven artefact id, the database schema, API
 * paths under {@code /api/...}, and configuration keys under {@code vam.*}.
 * Think of it like Chromium (codebase) vs Chrome (product).
 */

export const BRAND = {
  /** Short product name. Used in titles, wordmarks, headers. */
  name: 'Aperture',

  /** Headline brand promise. Used on the login screen and meta description. */
  tagline: 'See every flow, every account, every entity',

  /** Compact descriptor sitting under the wordmark in the sidebar. */
  shortSubtitle: 'Treasury Intelligence',

  /** Longer description for SEO / about screens. */
  description:
    'Corporate digital banking with virtual account management, multi-bank liquidity, and AI-assisted treasury.',

  /** Copyright footer year. */
  copyrightYear: 2024,

  /** Brand wordmark accent character — useful for favicons / loaders. */
  monogram: 'A',
} as const;

export type Brand = typeof BRAND;
