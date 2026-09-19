import type { Meta, StoryObj } from '@storybook/react-vite';

/**
 * Elevation & surfaces specimen.
 * - Shadows (the ONLY ones): shadow-sm rest, shadow-md hover/raised, shadow-lg popover (dropdowns/tooltips),
 *   shadow-xl modal (also shadow-2xl).
 * - Dark mode has no real shadows: depth comes from surface tone.
 * - Surfaces (light / dark): page bg-neutral-50 / bg-primary-950; card bg-white / bg-primary-900;
 *   muted bg-neutral-100 / bg-primary-800.
 * - Borders: default border-edge; strong (inputs) border-edge-strong.
 * Keep in sync with tailwind.config.js `boxShadow`.
 */
const SURFACES = [
  { name: 'Page', bg: 'bg-surface-page', tokens: 'bg-surface-page' },
  { name: 'Card', bg: 'bg-surface-card', tokens: 'bg-surface-card' },
  { name: 'Muted', bg: 'bg-surface-muted', tokens: 'bg-surface-muted' },
];
const DEFAULT_B = 'border border-edge';
const STRONG_B = 'border border-edge-strong';

const SHADOWS = [
  { name: 'Rest', cls: 'shadow-sm', use: 'Cards at rest' },
  { name: 'Hover / raised', cls: 'shadow-md', use: 'Hovered or raised cards' },
  { name: 'Popover', cls: 'shadow-lg', use: 'Dropdowns, tooltips' },
  { name: 'Modal', cls: 'shadow-xl', use: 'Modals (also shadow-2xl)' },
];
const SHADOW_CLS: Record<string, string> = { 'shadow-sm': 'shadow-sm', 'shadow-md': 'shadow-md', 'shadow-lg': 'shadow-lg', 'shadow-xl': 'shadow-xl' };

function Page() {
  return (
    <div className="p-8 space-y-12 max-w-5xl bg-surface-page min-h-screen">
      <p className="body-sm rounded-lg p-3 bg-info-50 dark:bg-info-500/10 text-info-700 dark:text-info-300 border border-info-200/60 dark:border-info-500/30">
        Dark mode has no real shadows: depth comes from surface tone (page → card → muted get progressively lighter).
      </p>

      <section>
        <h2 className="section-title mb-1">Surfaces</h2>
        <p className="caption mb-4">Each surface with the default and the strong border.</p>
        <div className="grid grid-cols-3 gap-4">
          {SURFACES.map((s) => (
            <div key={s.name} className="space-y-3">
              <div className="body-strong">{s.name}</div>
              <code className="code block">{s.tokens}</code>
              <div className={`rounded-lg p-4 body-sm ${s.bg} ${DEFAULT_B}`}>Default border</div>
              <div className={`rounded-lg p-4 body-sm ${s.bg} ${STRONG_B}`}>Strong border</div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="section-title mb-1">Shadow levels</h2>
        <p className="caption mb-4">Light mode shows the shadow; dark mode relies on tone and border.</p>
        <div className="grid grid-cols-4 gap-6">
          {SHADOWS.map((s) => (
            <div key={s.cls} className={`rounded-lg p-4 h-28 bg-surface-card ${DEFAULT_B} ${SHADOW_CLS[s.cls]}`}>
              <div className="body-strong">{s.name}</div>
              <code className="code">{s.cls}</code>
              <div className="caption mt-1">{s.use}</div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="section-title mb-1">Nesting</h2>
        <p className="caption mb-4">Page &gt; card &gt; muted: each level steps one tone, in both themes.</p>
        <div className="rounded-lg p-6 bg-surface-page border border-edge">
          <span className="label">Page</span>
          <div className={`mt-3 rounded-lg p-5 bg-surface-card shadow-sm ${DEFAULT_B}`}>
            <span className="label">Card</span>
            <div className={`mt-3 rounded-lg p-4 bg-surface-muted ${DEFAULT_B}`}>
              <span className="label">Muted</span>
              <p className="body-sm mt-1">Grouped detail inside a card.</p>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}

const meta: Meta = { title: 'Design System/Elevation & Surfaces', component: Page, parameters: { layout: 'fullscreen' } };
export default meta;
export const Specimens: StoryObj = { render: () => <Page /> };
