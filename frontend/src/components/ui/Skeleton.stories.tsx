import type { Meta, StoryObj } from '@storybook/react-vite';
import { Skeleton } from './index';

const meta: Meta<typeof Skeleton> = {
  title: 'Components/Skeleton',
  component: Skeleton,
  tags: ['autodocs'],
  args: { variant: 'text', width: '60%' },
  argTypes: {
    variant: { control: 'select', options: ['text', 'heading', 'circular', 'rectangular', 'card'] },
    lines: { control: { type: 'number', min: 1, max: 8 } },
  },
};
export default meta;
type Story = StoryObj<typeof Skeleton>;

export const Playground: Story = {};

export const Variants: Story = {
  render: () => (
    <div className="space-y-4 max-w-sm">
      <Skeleton variant="heading" width="50%" />
      <Skeleton variant="text" lines={3} />
      <div className="flex items-center gap-3">
        <Skeleton variant="circular" width={40} height={40} />
        <Skeleton variant="text" width="40%" />
      </div>
      <Skeleton variant="rectangular" height={80} />
      <Skeleton variant="card" height={120} />
    </div>
  ),
};
