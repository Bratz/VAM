import type { Meta, StoryObj } from '@storybook/react-vite';
import { CheckCircle, Loader2, Banknote, CreditCard } from 'lucide-react';
import { StatusIconBadge } from './StatusIconBadge';

const TONES = ['success', 'warning', 'error', 'info', 'primary', 'neutral', 'accent'] as const;
const SIZES = ['xs', 'sm', 'md', 'lg', 'xl'] as const;

const meta: Meta<typeof StatusIconBadge> = {
  title: 'Components/StatusIconBadge',
  component: StatusIconBadge,
  tags: ['autodocs'],
  args: { tone: 'success', icon: CheckCircle, size: 'md', rounded: 'lg', subtle: false },
  argTypes: {
    tone: { control: 'select', options: TONES },
    size: { control: 'select', options: SIZES },
    rounded: { control: 'select', options: ['lg', 'full'] },
    subtle: { control: 'boolean' },
    icon: { control: false },
  },
};
export default meta;
type Story = StoryObj<typeof StatusIconBadge>;

export const Playground: Story = {};

export const Matrix: Story = {
  render: () => (
    <div className="space-y-6">
      {(['lg', 'full'] as const).flatMap((r) =>
        [false, true].map((subtle) => (
          <div key={`${r}-${subtle}`} className="space-y-2">
            <p className="caption">rounded {r}{subtle ? ' / subtle' : ''}</p>
            {TONES.map((t) => (
              <div key={t} className="flex items-center gap-3">
                <span className="caption w-16">{t}</span>
                {SIZES.map((s) => (
                  <StatusIconBadge key={s} tone={t} icon={CheckCircle} size={s} rounded={r} subtle={subtle} />
                ))}
              </div>
            ))}
          </div>
        ))
      )}
    </div>
  ),
};

const CATS = ['cat-1', 'cat-2', 'cat-3', 'cat-4', 'cat-5', 'cat-6', 'cat-7', 'cat-8'] as const;

/** Categorical tones (hierarchy levels, account types): identity, not status. */
export const CategoryTones: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      {CATS.map((tone) => (
        <div key={tone} className="flex flex-col items-center gap-1">
          <StatusIconBadge tone={tone} icon={CheckCircle} />
          <span className="caption">{tone}</span>
        </div>
      ))}
    </div>
  ),
};

/** `solid` is the filled variant for selected / active states. */
export const Solid: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      {[...TONES, 'cat-1' as const, 'cat-2' as const].map((tone) => (
        <div key={tone} className="flex flex-col items-center gap-1">
          <StatusIconBadge tone={tone} icon={CheckCircle} solid />
          <span className="caption">{tone}</span>
        </div>
      ))}
    </div>
  ),
};

/** `spin` animates the icon: use with Loader2 for loading states. */
export const Spinning: Story = {
  render: () => (
    <div className="flex items-center gap-4">
      <StatusIconBadge tone="primary" icon={Loader2} size="lg" spin />
      <StatusIconBadge tone="primary" icon={Loader2} size="xl" spin />
    </div>
  ),
};

/** `inverse` is for badges on a dark hero or banner surface (tone is ignored). */
export const Inverse: Story = {
  render: () => (
    <div className="flex items-center gap-4 rounded-lg p-6 bg-primary-900 dark:bg-primary-950">
      <StatusIconBadge tone="neutral" icon={Banknote} size="lg" inverse />
      <StatusIconBadge tone="neutral" icon={Banknote} inverse />
      <span className="body-strong text-white">On a dark hero</span>
    </div>
  ),
};

/** `xs` (20px) is for legend keys and tree rows next to caption-sized text. */
export const ExtraSmall: Story = {
  render: () => (
    <div className="flex items-center gap-4 text-caption">
      {(['primary', 'success', 'warning', 'info'] as const).map((t) => (
        <span key={t} className="flex items-center gap-1.5">
          <StatusIconBadge tone={t} icon={CreditCard} size="xs" subtle />
          <span className="text-neutral-600 dark:text-neutral-300">{t}</span>
        </span>
      ))}
    </div>
  ),
};
