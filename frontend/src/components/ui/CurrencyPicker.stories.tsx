import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { CurrencyPicker } from './CurrencyPicker';

const meta: Meta<typeof CurrencyPicker> = {
  title: 'Components/CurrencyPicker',
  component: CurrencyPicker,
  tags: ['autodocs'],
  args: { value: 'GBP', withName: false, allowEmpty: false, disabled: false },
  argTypes: {
    withName: { control: 'boolean' },
    allowEmpty: { control: 'boolean' },
    disabled: { control: 'boolean' },
    onChange: { control: false },
  },
  decorators: [(Story) => <div className="max-w-xs"><Story /></div>],
  // Options come from MarketContext's default profile (no provider needed).
  render: function Render(args) {
    const [v, setV] = useState(args.value);
    return <CurrencyPicker {...args} value={v} onChange={setV} />;
  },
};
export default meta;
type Story = StoryObj<typeof CurrencyPicker>;

export const Playground: Story = {};
export const WithNames: Story = { args: { withName: true } };
export const FilterWithEmpty: Story = { args: { value: '', allowEmpty: true, emptyLabel: 'All currencies' } };
export const ExtraCurrency: Story = { args: { value: 'JPY', extra: ['JPY', 'CHF'], withName: true } };
export const Disabled: Story = { args: { disabled: true } };
