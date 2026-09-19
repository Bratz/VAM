import type { Meta, StoryObj } from '@storybook/react-vite';
import { Wallet } from 'lucide-react';
import { Card, CardHeader, StatusIconBadge } from './index';

const PADDINGS = ['none', 'xs', 'sm', 'md', 'lg', 'xl'] as const;

const meta: Meta<typeof Card> = {
  title: 'Components/Card',
  component: Card,
  tags: ['autodocs'],
  args: { padding: 'md', hover: false, interactive: false, bordered: true },
  argTypes: {
    padding: { control: 'select', options: PADDINGS },
    hover: { control: 'boolean' },
    interactive: { control: 'boolean' },
    gradient: { control: 'boolean' },
    bordered: { control: 'boolean' },
  },
  render: (args) => <Card {...args}><p className="body-sm">Card content</p></Card>,
};
export default meta;
type Story = StoryObj<typeof Card>;

export const Playground: Story = {};

export const PaddingSizes: Story = {
  render: () => (
    <div className="grid grid-cols-3 gap-4">
      {PADDINGS.map((p) => (
        <Card key={p} padding={p}><p className="body-sm">padding: {p}</p></Card>
      ))}
    </div>
  ),
};

export const Hover: Story = {
  render: () => (
    <div className="grid grid-cols-2 gap-4">
      <Card hover><p className="body-sm">hover</p></Card>
      <Card interactive><p className="body-sm">interactive</p></Card>
    </div>
  ),
};

export const WithHeader: Story = {
  render: () => (
    <Card>
      <CardHeader
        title="Liquidity by currency"
        subtitle="Across all banks"
        icon={<StatusIconBadge tone="primary" icon={Wallet} />}
      />
      <p className="body-sm">Body content goes here.</p>
    </Card>
  ),
};
