import type { Meta, StoryObj } from '@storybook/react-vite';
import { Plus, ArrowRight } from 'lucide-react';
import { Button } from './index';

const VARIANTS = ['primary', 'secondary', 'outline', 'ghost', 'danger', 'success', 'accent'] as const;
const SIZES = ['xs', 'sm', 'md', 'lg', 'xl'] as const;

const meta: Meta<typeof Button> = {
  title: 'Components/Button',
  component: Button,
  tags: ['autodocs'],
  args: { children: 'Button', variant: 'primary', size: 'md' },
  argTypes: {
    variant: { control: 'select', options: VARIANTS },
    size: { control: 'select', options: SIZES },
    loading: { control: 'boolean' },
    disabled: { control: 'boolean' },
    fullWidth: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Button>;

export const Playground: Story = {};

export const VariantsBySize: Story = {
  render: () => (
    <div className="space-y-4">
      {VARIANTS.map((v) => (
        <div key={v} className="flex flex-wrap items-center gap-3">
          <span className="caption w-20">{v}</span>
          {SIZES.map((s) => (
            <Button key={s} variant={v} size={s}>{s}</Button>
          ))}
        </div>
      ))}
    </div>
  ),
};

export const WithIcons: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      <Button leftIcon={<Plus />}>Add account</Button>
      <Button variant="outline" rightIcon={<ArrowRight />}>Continue</Button>
      <Button variant="secondary" leftIcon={<Plus />} rightIcon={<ArrowRight />}>Both</Button>
    </div>
  ),
};

export const States: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      {VARIANTS.map((v) => (
        <Button key={v} variant={v} loading>{v}</Button>
      ))}
      {VARIANTS.map((v) => (
        <Button key={`d-${v}`} variant={v} disabled>{v}</Button>
      ))}
    </div>
  ),
};
