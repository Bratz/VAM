import type { Meta, StoryObj } from '@storybook/react-vite';
import { Banknote } from 'lucide-react';
import { HeroMetricCard } from './HeroMetricCard';

const meta: Meta<typeof HeroMetricCard> = {
  title: 'Components/HeroMetricCard',
  component: HeroMetricCard,
  tags: ['autodocs'],
  args: {
    primary: { label: 'Total balance', value: 'GBP 12,481,930.15', trend: '+8.2%', trendTone: 'success', sub: '14 accounts across 3 banks' },
    icon: <Banknote className="w-6 h-6 text-accent-700 dark:text-accent-300" />,
  },
  argTypes: { icon: { control: false } },
};
export default meta;
type Story = StoryObj<typeof HeroMetricCard>;

export const Playground: Story = {};

export const WithSecondary: Story = {
  args: {
    secondary: { label: 'Available', value: 'GBP 11,204,118.90', sub: 'After GBP 1.2M holds' },
  },
};

export const NegativeTrendNoIcon: Story = {
  args: {
    icon: undefined,
    primary: { label: 'Net position', value: 'USD 3,912,004.60', trend: '-3.1%', trendTone: 'error', sub: 'vs. last week' },
  },
};

export const NeutralTrend: Story = {
  args: { primary: { label: 'Pool balance', value: 'GBP 6,440,000.00', trend: '0.0%', trendTone: 'neutral' } },
};
