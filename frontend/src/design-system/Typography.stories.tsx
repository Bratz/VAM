import type { Meta, StoryObj } from '@storybook/react-vite';

/**
 * Type specimen — the ONLY approved way to set text size in the app.
 * Prefer a semantic class (.caption, .body-sm, .label, ...). When a class doesn't fit
 * (e.g. inherited colour), use the size utility (text-caption, text-body-sm, ...).
 * Raw text-xs/sm/base/lg/xl/2xl.. and text-[Npx] are not in the Tailwind scale and are lint errors.
 * Single source for this page: keep in sync with tailwind.config.js `fontSize` and styles/index.css.
 */
const SIZES = [
  { util: 'text-caption',    px: '12/16', was: 'text-xs' },
  { util: 'text-body-sm',    px: '14/20', was: 'text-sm' },
  { util: 'text-body',       px: '16/24', was: 'text-base' },
  { util: 'text-body-lg',    px: '18/28', was: 'text-lg' },
  { util: 'text-heading-sm', px: '20/28', was: 'text-xl' },
  { util: 'text-heading-md', px: '24/32', was: 'text-2xl' },
  { util: 'text-heading-lg', px: '30/36', was: 'text-3xl' },
  { util: 'text-stat-sm',    px: '28/36', was: 'text-[28px]' },
  { util: 'text-stat',       px: '36/40', was: 'text-4xl' },
  { util: 'text-display',    px: '48/48', was: 'text-5xl' },
];

const CLASSES = [
  { cls: 'page-title',       use: 'Page H1',                          sample: 'Multi-Bank Liquidity' },
  { cls: 'section-title',    use: 'Card / section heading',            sample: 'Liquidity by currency' },
  { cls: 'stat-value',       use: 'Hero figure',                       sample: 'GBP 1,240,500.00' },
  { cls: 'stat-value-sm',    use: 'StatTile headline',                 sample: 'GBP 84,210.00' },
  { cls: 'stat-value-xs',    use: 'Secondary figure in a dense card',  sample: 'GBP 12,400.00' },
  { cls: 'stat-value-success', use: 'Tone-coloured StatTile figure',   sample: '+GBP 3,200.00' },
  { cls: 'stat-value-error', use: 'Tone-coloured StatTile figure',     sample: '-GBP 3,200.00' },
  { cls: 'body-lg',          use: 'Intro / empty-state heading',       sample: 'No transactions match your filters' },
  { cls: 'body',             use: 'Default copy',                      sample: 'Payments are released after approval.' },
  { cls: 'body-strong',      use: 'Names, row titles (+ font-semibold for heavier)', sample: 'Acme Treasury Ltd' },
  { cls: 'body-sm',          use: 'Supporting text',                   sample: 'HSBC UK Current · 40-11-22 12345678' },
  { cls: 'field-label',      use: 'Form field label',                  sample: 'Beneficiary name' },
  { cls: 'label',            use: 'Uppercase eyebrow / column header', sample: 'Net amount' },
  { cls: 'label-cased',      use: 'Micro-label, cased',                sample: 'Beta' },
  { cls: 'overline',         use: 'Section eyebrow',                   sample: 'Accounts & structure' },
  { cls: 'caption',          use: 'Helper text, footnotes',            sample: 'Updated 2 minutes ago' },
  { cls: 'caption-success', use: 'Status-toned helper text', sample: 'Matched' },
  { cls: 'caption-warning', use: 'Status-toned helper text', sample: 'Awaiting approval' },
  { cls: 'caption-error', use: 'Status-toned helper text', sample: 'Insufficient funds' },
  { cls: 'caption-info', use: 'Status-toned helper text', sample: 'Scheduled for tomorrow' },
  { cls: 'code',             use: 'Account numbers, IDs',              sample: 'VA-GB-000123-USD' },
];

const th = 'text-left label pb-2 pr-6';

function Specimen() {
  return (
    <div className="space-y-12 max-w-5xl">
      <section>
        <h2 className="section-title mb-1">Semantic classes</h2>
        <p className="caption mb-4">Use these first — they bundle size, weight, colour, family and dark mode.</p>
        <table className="w-full">
          <thead><tr><th className={th}>Class</th><th className={th}>Use</th><th className={th}>Sample</th></tr></thead>
          <tbody className="divide-y divide-edge">
            {CLASSES.map((c) => (
              <tr key={c.cls}>
                <td className="py-3 pr-6 align-baseline"><code className="code">.{c.cls}</code></td>
                <td className="py-3 pr-6 align-baseline caption">{c.use}</td>
                <td className="py-3 align-baseline"><span className={c.cls}>{c.sample}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <h2 className="section-title mb-1">Size utilities</h2>
        <p className="caption mb-4">For inherited-colour text where no semantic class fits. This is the entire scale.</p>
        <table className="w-full">
          <thead><tr><th className={th}>Utility</th><th className={th}>px / line-height</th><th className={th}>Replaces</th><th className={th}>Sample</th></tr></thead>
          <tbody className="divide-y divide-edge">
            {SIZES.map((s) => (
              <tr key={s.util}>
                <td className="py-3 pr-6 align-baseline"><code className="code">{s.util}</code></td>
                <td className="py-3 pr-6 align-baseline caption">{s.px}</td>
                <td className="py-3 pr-6 align-baseline caption line-through">{s.was}</td>
                <td className={`py-3 align-baseline text-primary-900 dark:text-neutral-50 ${s.util}`}>Every flow, every account</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

const meta: Meta = { title: 'Design System/Typography', component: Specimen, parameters: { layout: 'fullscreen' } };
export default meta;
export const Specimens: StoryObj = {};
