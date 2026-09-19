import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Wallet, TrendingUp, AlertTriangle, Building2, Info, CheckCircle } from 'lucide-react';
import { StatTile } from './StatTile';
import { StatStrip } from '../layout/StatStrip';

const TONES = ['primary', 'success', 'warning', 'danger', 'info', 'accent', 'neutral'] as const;

const meta: Meta<typeof StatTile> = {
  title: 'Components/StatTile',
  component: StatTile,
  tags: ['autodocs'],
  args: {
    tone: 'primary',
    label: 'Total balance',
    value: 'GBP 2,481,930.15',
    sub: '14 accounts',
    layout: 'stack',
    icon: <Wallet className="w-5 h-5" />,
  },
  argTypes: {
    tone: { control: 'select', options: TONES },
    valueTone: { control: 'select', options: [undefined, ...TONES] },
    layout: { control: 'radio', options: ['stack', 'row'] },
    loading: { control: 'boolean' },
    active: { control: 'boolean' },
    icon: { control: false },
    onClick: { control: false },
  },
  decorators: [(Story) => <div className="max-w-xs"><Story /></div>],
};
export default meta;
type Story = StoryObj<typeof StatTile>;

export const Playground: Story = {};

export const Tones: Story = {
  decorators: [(Story) => <div className="max-w-none"><Story /></div>],
  render: () => (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {TONES.map((t) => (
        <StatTile key={t} tone={t} label={t} value="GBP 84,120.00" icon={<Info className="w-5 h-5" />} />
      ))}
    </div>
  ),
};

export const WithoutIcon: Story = { args: { icon: undefined } };

export const RowLayout: Story = {
  args: { layout: 'row', tone: 'success', label: 'Cleared today', value: '312', sub: 'of 318 payments', icon: <CheckCircle className="w-5 h-5" /> },
};

export const Loading: Story = { args: { loading: true } };

export const ValueTone: Story = {
  args: { tone: 'info', valueTone: 'success', label: 'Total savings', value: 'USD 41,250.00', icon: <TrendingUp className="w-5 h-5" /> },
};

export const LongCurrencyValueShrinks: Story = {
  args: { value: 'GBP 14,437,919,204,388.42', label: 'Pooled liquidity', sub: 'Value shrinks to fit, floor 14px' },
};

export const ClickableFilter: Story = {
  decorators: [(Story) => <div className="max-w-none"><Story /></div>],
  render: function Render() {
    const [active, setActive] = useState('all');
    const items = [
      { id: 'all', label: 'All invoices', value: '128', tone: 'primary', icon: <Building2 className="w-5 h-5" /> },
      { id: 'overdue', label: 'Overdue', value: '9', tone: 'danger', icon: <AlertTriangle className="w-5 h-5" /> },
      { id: 'paid', label: 'Paid', value: '104', tone: 'success', icon: <CheckCircle className="w-5 h-5" /> },
    ] as const;
    return (
      <div className="grid grid-cols-3 gap-4 max-w-2xl">
        {items.map((i) => (
          <StatTile key={i.id} tone={i.tone} label={i.label} value={i.value} icon={i.icon} active={active === i.id} onClick={() => setActive(i.id)} />
        ))}
      </div>
    );
  },
};

export const SixTileStripNarrow: Story = {
  decorators: [(Story) => <div className="max-w-none"><Story /></div>],
  render: () => (
    <div className="w-[640px] max-w-full">
      <StatStrip columns={6}>
        <StatTile tone="primary" label="Balance" value="GBP 2,481,930.15" icon={<Wallet className="w-5 h-5" />} />
        <StatTile tone="success" label="Inflows" value="GBP 912,004.00" icon={<TrendingUp className="w-5 h-5" />} />
        <StatTile tone="warning" label="Pending" value="USD 58,300.75" icon={<AlertTriangle className="w-5 h-5" />} />
        <StatTile tone="info" label="Accounts" value="14" icon={<Building2 className="w-5 h-5" />} />
        <StatTile tone="danger" label="Overdue" value="GBP 1,204,118.90" icon={<AlertTriangle className="w-5 h-5" />} />
        <StatTile tone="accent" label="Sweeps" value="GBP 6,440,000.00" icon={<Wallet className="w-5 h-5" />} />
      </StatStrip>
    </div>
  ),
};
