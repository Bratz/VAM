import type { Meta, StoryObj } from '@storybook/react-vite';
import { Inbox } from 'lucide-react';
import { EmptyState, Button } from './index';

const meta: Meta<typeof EmptyState> = {
  title: 'Components/EmptyState',
  component: EmptyState,
  tags: ['autodocs'],
  args: {
    icon: <Inbox className="w-8 h-8" />,
    title: 'No payments yet',
    description: 'Payments you create will appear here.',
    action: <Button>Create payment</Button>,
    compact: false,
  },
  argTypes: { compact: { control: 'boolean' } },
};
export default meta;
type Story = StoryObj<typeof EmptyState>;

export const Default: Story = {};
export const Compact: Story = { args: { compact: true, icon: <Inbox className="w-6 h-6" /> } };
export const TitleOnly: Story = { args: { icon: undefined, description: undefined, action: undefined } };
