import type { Meta, StoryObj } from '@storybook/react-vite';
import { CheckCircle } from 'lucide-react';
import { Badge } from './index';

const VARIANTS = ['success', 'warning', 'error', 'info', 'neutral', 'primary', 'accent'] as const;
const SIZES = ['xs', 'sm', 'md'] as const;

const meta: Meta<typeof Badge> = {
  title: 'Components/Badge',
  component: Badge,
  tags: ['autodocs'],
  args: { children: 'Active', variant: 'success', size: 'md' },
  argTypes: {
    variant: { control: 'select', options: VARIANTS },
    size: { control: 'select', options: SIZES },
    dot: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Badge>;

export const Playground: Story = {};

export const VariantsBySize: Story = {
  render: () => (
    <div className="space-y-3">
      {VARIANTS.map((v) => (
        <div key={v} className="flex items-center gap-3">
          <span className="caption w-20">{v}</span>
          {SIZES.map((s) => <Badge key={s} variant={v} size={s}>{v}</Badge>)}
        </div>
      ))}
    </div>
  ),
};

export const WithDotAndIcon: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      {VARIANTS.map((v) => <Badge key={v} variant={v} dot>{v}</Badge>)}
      {VARIANTS.map((v) => <Badge key={`i-${v}`} variant={v} icon={<CheckCircle className="w-3 h-3" />}>{v}</Badge>)}
    </div>
  ),
};
