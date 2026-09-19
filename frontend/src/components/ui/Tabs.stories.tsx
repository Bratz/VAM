import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Inbox, Send } from 'lucide-react';
import { Tabs } from './enhanced';

const TABS = [
  { id: 'all', label: 'All', icon: <Inbox className="w-4 h-4" />, badge: 12 },
  { id: 'sent', label: 'Sent', icon: <Send className="w-4 h-4" /> },
  { id: 'drafts', label: 'Drafts', badge: 3 },
  { id: 'archived', label: 'Archived', disabled: true },
];

const meta: Meta<typeof Tabs> = {
  title: 'Components/Tabs',
  component: Tabs,
  tags: ['autodocs'],
  argTypes: {
    variant: { control: 'select', options: ['default', 'pills', 'underline'] },
    size: { control: 'select', options: ['sm', 'md'] },
    fullWidth: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Tabs>;

const Demo = (p: { variant?: 'default' | 'pills' | 'underline'; size?: 'sm' | 'md'; fullWidth?: boolean }) => {
  const [active, setActive] = useState('all');
  return <Tabs tabs={TABS} activeTab={active} onChange={setActive} {...p} />;
};

export const Playground: Story = { render: (args) => <Demo {...args} /> };

export const AllVariants: Story = {
  render: () => (
    <div className="space-y-8">
      {(['default', 'pills', 'underline'] as const).map((v) => (
        <div key={v} className="space-y-2">
          <p className="caption">{v}</p>
          <Demo variant={v} />
          <Demo variant={v} size="sm" />
        </div>
      ))}
    </div>
  ),
};
