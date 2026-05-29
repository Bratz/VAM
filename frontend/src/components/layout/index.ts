/**
 * Aperture layout primitives. Phase 10 Design System Unification.
 *
 * Layout — app shell (sidebar + header + main + mobile bottom-nav).
 *          Owns navigation chrome, page-header actions registration,
 *          theme toggle, entity picker. The single root the App renders.
 * Page    — page wrapper. max-width + space-y-6. Use as the root of any
 *          page body. Three width modes: default (1280), narrow (1024),
 *          full (uncapped).
 * PageHeader — title + description + actions. Renders title in .page-title
 *          (Fraunces 30px / gold underline). First child of <Page> when
 *          the page has a body heading.
 * StatStrip — top-of-page metric tile row. Auto-derives column count
 *          from child count. Standardizes gap-4 across pages.
 */
export { Layout } from './Layout';
export { default } from './Layout';
export * from './Page';
export * from './PageHeader';
export * from './StatStrip';
