import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Toggle } from './Toggle';

const meta: Meta<typeof Toggle> = {
  title: 'Components/Toggle',
  component: Toggle,
  tags: ['autodocs'],
  args: { label: 'Email notifications', description: 'Send a daily summary.', size: 'md', disabled: false },
  argTypes: { size: { control: 'select', options: ['sm', 'md'] }, disabled: { control: 'boolean' }, error: { control: 'text' } },
};
export default meta;
type Story = StoryObj<typeof Toggle>;

export const Playground: Story = {
  render: function Render(args) {
    const [on, setOn] = useState(false);
    return <Toggle {...args} checked={on} onChange={setOn} />;
  },
};

export const Matrix: Story = {
  render: () => (
    <div className="space-y-4">
      {(['sm', 'md'] as const).map((s) => (
        <div key={s} className="flex flex-wrap gap-6">
          <Toggle size={s} label={`${s} off`} checked={false} onChange={() => {}} />
          <Toggle size={s} label={`${s} on`} checked onChange={() => {}} />
          <Toggle size={s} label={`${s} disabled`} disabled checked={false} onChange={() => {}} />
          <Toggle size={s} label={`${s} disabled on`} disabled checked onChange={() => {}} />
          <Toggle size={s} label={`${s} error`} error="Required" checked={false} onChange={() => {}} />
        </div>
      ))}
    </div>
  ),
};

/** `layout="split"`: full-width settings row with the label on the left and the switch on the right. */
export const Split: Story = {
  render: function Render() {
    const [a, setA] = useState(true);
    const [b, setB] = useState(false);
    return (
      <div className="max-w-md divide-y divide-neutral-200 dark:divide-primary-800">
        <div className="py-3"><Toggle layout="split" label="Apply VAT" description="Add VAT to the invoice total" checked={a} onChange={setA} /></div>
        <div className="py-3"><Toggle layout="split" label="Reverse charge" description="Buyer accounts for VAT" checked={b} onChange={setB} /></div>
      </div>
    );
  },
};
