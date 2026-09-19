import type { Meta, StoryObj } from '@storybook/react-vite';
import { Building2 } from 'lucide-react';
import { Badge, Button, Card, Input } from '../components/ui';

/**
 * Shape specimen.
 * - Radius scale (the ONLY radii): rounded-sm 4 (checkboxes, code chips), rounded-md 8 (tooltips, small tags),
 *   rounded-lg 12 (controls & surfaces: buttons, inputs, cards, modals, popovers, medallions),
 *   rounded-full (pills, badges, avatars, dots). rounded-xl/2xl/3xl and bare `rounded` do not exist.
 * - Status pill = <Badge> (rounded-full + 1px tinted border). Category tag = rounded-md tinted chip.
 * - Status dots: w-2 h-2 rounded-full (w-1.5 only inside a badge).
 * - Borders: 1px everywhere; 2px only for active-tab underline, selected and focus.
 * - Focus ring: focus-visible:ring-2 ring-offset-2.
 * Keep in sync with tailwind.config.js `borderRadius`.
 */
const RADII = [
  { cls: 'rounded-sm', px: '4px', use: 'Checkboxes, code chips' },
  { cls: 'rounded-md', px: '8px', use: 'Tooltips, small tags' },
  { cls: 'rounded-lg', px: '12px', use: 'Buttons, inputs, cards, modals, popovers, medallions' },
  { cls: 'rounded-full', px: '9999px', use: 'Pills, badges, avatars, dots' },
] as const;
const SWATCH: Record<string, string> = {
  'rounded-sm': 'rounded-sm', 'rounded-md': 'rounded-md', 'rounded-lg': 'rounded-lg', 'rounded-full': 'rounded-full',
};
const th = 'text-left label pb-2 pr-6';
const rows = 'divide-y divide-neutral-200 dark:divide-primary-800';
const surface = 'bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800';
const tag = 'inline-block rounded-md px-2 py-0.5 text-caption bg-neutral-100 dark:bg-primary-800 text-neutral-700 dark:text-neutral-200';

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="section-title mb-1">{title}</h2>
      {hint && <p className="caption mb-4">{hint}</p>}
      {children}
    </section>
  );
}

function Page() {
  const map: { comp: string; cls: string; demo: React.ReactNode }[] = [
    { comp: 'Button', cls: 'rounded-lg', demo: <Button size="sm">Save</Button> },
    { comp: 'Input', cls: 'rounded-lg', demo: <Input placeholder="Account name" inputSize="sm" /> },
    { comp: 'Select-like', cls: 'rounded-lg', demo: <div className="rounded-lg border border-neutral-300 dark:border-primary-700 bg-white dark:bg-primary-900 px-3 py-2 body-sm w-40">GBP</div> },
    { comp: 'Card', cls: 'rounded-lg', demo: <Card padding="sm"><span className="body-sm">Card</span></Card> },
    { comp: 'Badge', cls: 'rounded-full', demo: <Badge variant="success">Active</Badge> },
    { comp: 'Tag chip', cls: 'rounded-md', demo: <span className={tag}>Treasury</span> },
    { comp: 'Avatar', cls: 'rounded-full', demo: <div className="w-8 h-8 rounded-full bg-primary-700 text-white flex items-center justify-center caption">SB</div> },
    { comp: 'Tooltip-like', cls: 'rounded-md', demo: <div className="rounded-md bg-primary-900 dark:bg-primary-700 text-white px-2 py-1 text-caption shadow-lg inline-block">Copied</div> },
    { comp: 'Code chip', cls: 'rounded-sm', demo: <code className="code rounded-sm bg-neutral-100 dark:bg-primary-800 px-1">VA-GB-001</code> },
  ];
  return (
    <div className="p-8 space-y-12 max-w-5xl bg-neutral-50 dark:bg-primary-950 min-h-screen">
      <Section title="Radius scale" hint="This is the entire scale.">
        <div className="flex gap-6 flex-wrap">
          {RADII.map((r) => (
            <div key={r.cls} className="w-44">
              <div className={`h-20 bg-primary-100 dark:bg-primary-700 border border-primary-300 dark:border-primary-600 ${SWATCH[r.cls]}`} />
              <code className="code block mt-2">{r.cls}</code>
              <div className="caption">{r.px} · {r.use}</div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Component to radius">
        <table className="w-full">
          <thead><tr><th className={th}>Component</th><th className={th}>Radius</th><th className={th}>Sample</th></tr></thead>
          <tbody className={rows}>
            {map.map((m) => (
              <tr key={m.comp}>
                <td className="py-3 pr-6 body-strong">{m.comp}</td>
                <td className="py-3 pr-6"><code className="code">{m.cls}</code></td>
                <td className="py-3">{m.demo}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="Pill vs tag vs dot" hint="Status = pill (round). Category = rectangular tag. Dots are w-2 h-2.">
        <div className="flex items-center gap-8 flex-wrap">
          <div><div className="label mb-2">Status pill</div><Badge variant="warning" dot>Pending</Badge></div>
          <div><div className="label mb-2">Category tag</div><span className={tag}>Payments</span></div>
          <div>
            <div className="label mb-2">Status dots</div>
            <div className="flex items-center gap-2 body-sm">
              <span className="w-2 h-2 rounded-full bg-success-500" />Online
              <span className="w-2 h-2 rounded-full bg-error-500 ml-3" />Offline
            </div>
          </div>
        </div>
      </Section>

      <Section title="Borders" hint="1px everywhere; 2px only for active tab, selected and focus.">
        <div className="flex gap-6 flex-wrap">
          <div className={`rounded-lg p-4 body-sm ${surface}`}>1px border (default)</div>
          <div className="rounded-lg p-4 body-sm bg-white dark:bg-primary-900 border-2 border-primary-600 dark:border-primary-400">2px selected</div>
          <div className="flex gap-4 body-sm">
            <span className="pb-2 border-b-2 border-primary-600 dark:border-primary-400 text-primary-900 dark:text-neutral-50">Active tab</span>
            <span className="pb-2 border-b border-transparent text-neutral-500">Inactive tab</span>
          </div>
        </div>
      </Section>

      <Section title="Focus ring" hint="Tab to the button, or see the forced sample.">
        <div className="flex items-center gap-6 flex-wrap">
          <Button variant="outline" leftIcon={<Building2 className="w-4 h-4" />}>Focus me (Tab)</Button>
          <div className="rounded-lg px-4 py-2 body-sm bg-white dark:bg-primary-900 ring-2 ring-primary-500 ring-offset-2 ring-offset-neutral-50 dark:ring-offset-primary-950">
            ring-2 ring-offset-2
          </div>
        </div>
      </Section>
    </div>
  );
}

const meta: Meta = { title: 'Design System/Shapes', component: Page, parameters: { layout: 'fullscreen' } };
export default meta;
export const Specimens: StoryObj = { render: () => <Page /> };
