import type { Meta, StoryObj } from '@storybook/react-vite';
import { Plus, Download, ChevronRight } from 'lucide-react';
import { Button } from '../components/ui';
import { StatusIconBadge } from '../components/ui/StatusIconBadge';
import { ICONS, ICON_SIZE, ICON_PX, STATUS_ICONS } from './icons';
import type { StatusTone } from './icons';

/**
 * Iconography specimen.
 * - ONLY lucide-react. No custom SVGs, no raw glyphs (✓ × ⚠️).
 * - Sizes: xs 12 (w-3), sm 16 (w-4, default inline/buttons), md 20 (w-5, nav/headers),
 *   lg 24 (w-6, cards), xl 32 (w-8, hero), 48 (w-12) only for empty-state illustrations.
 * - Stroke stays the lucide default (2). Colour via currentColor: `text-*` on the parent, never props.
 * - One canonical icon per concept (registry in ./icons.ts).
 * - Status tones: StatusIconBadge (sm 32 / md 40 / lg 48 / xl 64 box), rounded 'lg' | 'full', `subtle`.
 * Keep in sync with design-system/icons.ts.
 */
const SIZES = ['xs', 'sm', 'md', 'lg', 'xl', 'hero'] as const;
const TONES: StatusTone[] = ['success', 'warning', 'error', 'info'];
const BADGE_SIZES = ['sm', 'md', 'lg', 'xl'] as const;
const BTN_SIZES = ['xs', 'sm', 'md', 'lg', 'xl'] as const;
const th = 'text-left label pb-2 pr-4';
const rows = 'divide-y divide-edge';
const iconColor = 'text-primary-700 dark:text-neutral-200';

function Grid() {
  return (
    <section>
      <h2 className="section-title mb-1">Canonical icons</h2>
      <p className="caption mb-4">One icon per concept, shown at every size.</p>
      <table className="w-full">
        <thead>
          <tr>
            <th className={th}>Concept</th><th className={th}>Component</th>
            {SIZES.map((s) => <th key={s} className={th}>{s} · {ICON_PX[s]}px</th>)}
          </tr>
        </thead>
        <tbody className={rows}>
          {ICONS.map(({ concept, name, icon: Icon, note }) => (
            <tr key={name}>
              <td className="py-2 pr-4"><div className="body-strong">{concept}</div><div className="caption">{note}</div></td>
              <td className="py-2 pr-4"><code className="code">{name}</code></td>
              {SIZES.map((s) => (
                <td key={s} className={`py-2 pr-4 ${iconColor}`}><Icon className={ICON_SIZE[s]} /></td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="caption mt-3">
        Classes: {SIZES.map((s) => <code key={s} className="code mr-3">{ICON_SIZE[s]}</code>)}
      </p>
    </section>
  );
}

function Status() {
  return (
    <section>
      <h2 className="section-title mb-1">Status icons</h2>
      <p className="caption mb-4">StatusIconBadge per tone, by size; then rounded=full and subtle.</p>
      <div className="space-y-4">
        {TONES.map((tone) => {
          const Icon = STATUS_ICONS[tone];
          return (
            <div key={tone} className="flex items-center gap-4 flex-wrap">
              <code className="code w-20">{tone}</code>
              {BADGE_SIZES.map((s) => <StatusIconBadge key={s} tone={tone} icon={Icon} size={s} />)}
              <span className="caption">full</span>
              {BADGE_SIZES.map((s) => <StatusIconBadge key={`f${s}`} tone={tone} icon={Icon} size={s} rounded="full" />)}
              <span className="caption">subtle</span>
              {BADGE_SIZES.map((s) => <StatusIconBadge key={`s${s}`} tone={tone} icon={Icon} size={s} subtle />)}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function DoDont() {
  const good = ICONS.find((i) => i.name === 'CheckCircle')!.icon;
  const Good = good;
  const box = 'rounded-lg border border-edge bg-surface-card p-4 space-y-3';
  return (
    <section className="grid md:grid-cols-2 gap-4">
      <div className={box}>
        <h3 className="section-title">Do</h3>
        <ul className="body-sm space-y-2">
          <li className="flex items-center gap-2 text-success-600 dark:text-success-300"><Good className="w-4 h-4" /> lucide icon, scale size, colour from parent text-*</li>
          <li className="flex items-center gap-2"><Good className="w-5 h-5 text-success-600 dark:text-success-300" /> Same icon per concept everywhere</li>
        </ul>
      </div>
      <div className={box}>
        <h3 className="section-title">Don't</h3>
        <ul className="body-sm space-y-2">
          <li>Raw glyphs: <span className="line-through">✓ × ⚠️</span> - use Check, X, AlertTriangle</li>
          <li>Custom or hand-drawn SVGs - use lucide-react</li>
          <li>Colour props: <code className="code line-through">{'<Check color="green" />'}</code> - use text-* classes</li>
          <li>Off-scale sizes: <code className="code line-through">w-[18px]</code>, <code className="code line-through">w-7 h-7</code></li>
          <li>Changing <code className="code">strokeWidth</code> - stay at the default 2</li>
          <li>Hand-built icon tiles - use <code className="code">StatusIconBadge</code> (tone, subtle, solid, inverse, spin, xs-xl)</li>
        </ul>
      </div>
      <div className={`${box} md:col-span-2`}>
        <h3 className="section-title">Allowed exceptions</h3>
        <ul className="body-sm space-y-2">
          <li>Brand marks: the sidebar logo and the Copilot avatar / launcher.</li>
          <li>Third-party logos: integration connector logos (<code className="code">components/ConnectorIcons.tsx</code>, lucide ships no brand logos) and the frames around them, plus bank logos in Open Banking setup.</li>
        </ul>
      </div>
    </section>
  );
}

function Buttons() {
  return (
    <section>
      <h2 className="section-title mb-1">Icons in buttons</h2>
      <p className="caption mb-4">leftIcon / rightIcon use w-4 h-4 at every button size unless noted.</p>
      <div className="space-y-3">
        {BTN_SIZES.map((s) => (
          <div key={s} className="flex items-center gap-3 flex-wrap">
            <code className="code w-10">{s}</code>
            <Button size={s} leftIcon={<Plus className="w-4 h-4" />}>Add</Button>
            <Button size={s} variant="outline" rightIcon={<ChevronRight className="w-4 h-4" />}>Next</Button>
            <Button size={s} variant="secondary" leftIcon={<Download className="w-4 h-4" />} rightIcon={<ChevronRight className="w-4 h-4" />}>Export</Button>
          </div>
        ))}
      </div>
    </section>
  );
}

function Page() {
  return (
    <div className="p-8 space-y-12 max-w-6xl bg-surface-page min-h-screen">
      <Grid /><Status /><DoDont /><Buttons />
    </div>
  );
}

const meta: Meta = { title: 'Design System/Iconography', component: Page, parameters: { layout: 'fullscreen' } };
export default meta;
export const Specimens: StoryObj = { render: () => <Page /> };
