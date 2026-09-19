import type { Meta, StoryObj } from '@storybook/react-vite';
import { Avatar } from './enhanced';

const SIZES = ['xs', 'sm', 'md', 'lg', 'xl'] as const;

const meta: Meta<typeof Avatar> = {
  title: 'Components/Avatar',
  component: Avatar,
  tags: ['autodocs'],
  args: { name: 'Priya Raman', size: 'md', variant: 'circle' },
  argTypes: {
    size: { control: 'select', options: SIZES },
    variant: { control: 'radio', options: ['circle', 'rounded-md'] },
    status: { control: 'select', options: [undefined, 'online', 'offline', 'busy', 'away'] },
  },
};
export default meta;
type Story = StoryObj<typeof Avatar>;

export const Playground: Story = {};

export const Sizes: Story = {
  render: () => (
    <div className="flex items-center gap-3">
      {SIZES.map((s) => <Avatar key={s} name="Priya Raman" size={s} />)}
    </div>
  ),
};

export const StatusAndVariants: Story = {
  render: () => (
    <div className="flex items-center gap-3">
      <Avatar name="Daniel Okafor" status="online" />
      <Avatar name="Mei Tanaka" status="busy" />
      <Avatar name="Tom Hale" status="away" />
      <Avatar name="Sara Lindqvist" status="offline" variant="rounded-md" />
      <Avatar name="Halcyon Logistics" variant="rounded-md" />
    </div>
  ),
};
