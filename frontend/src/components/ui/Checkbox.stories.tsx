import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Checkbox } from './Checkbox';

const meta: Meta<typeof Checkbox> = {
  title: 'Components/Checkbox',
  component: Checkbox,
  tags: ['autodocs'],
  args: { label: 'Accept terms', description: 'You agree to the terms of service.', size: 'md', disabled: false, indeterminate: false },
  argTypes: {
    size: { control: 'select', options: ['sm', 'md'] },
    disabled: { control: 'boolean' },
    indeterminate: { control: 'boolean' },
    error: { control: 'text' },
  },
};
export default meta;
type Story = StoryObj<typeof Checkbox>;

export const Playground: Story = {
  render: function Render(args) {
    const [on, setOn] = useState(false);
    return <Checkbox {...args} checked={on} onChange={setOn} />;
  },
};

export const Indeterminate: Story = {
  render: function Render() {
    const [items, setItems] = useState([true, false, false]);
    const all = items.every(Boolean);
    const some = items.some(Boolean) && !all;
    return (
      <div className="space-y-2">
        <Checkbox label="Select all" checked={all} indeterminate={some} onChange={(c) => setItems(items.map(() => c))} />
        <div className="pl-6 space-y-2">
          {items.map((v, i) => (
            <Checkbox key={i} label={`Item ${i + 1}`} checked={v} onChange={(c) => setItems(items.map((x, j) => (j === i ? c : x)))} />
          ))}
        </div>
      </div>
    );
  },
};

export const States: Story = {
  render: () => (
    <div className="flex flex-wrap gap-6">
      <Checkbox size="sm" label="sm" checked onChange={() => {}} />
      <Checkbox label="md" checked onChange={() => {}} />
      <Checkbox label="disabled" disabled checked={false} onChange={() => {}} />
      <Checkbox label="disabled checked" disabled checked onChange={() => {}} />
      <Checkbox label="error" error="Required" checked={false} onChange={() => {}} />
    </div>
  ),
};

/** `variant="card"`: a bordered option row that tints when checked (for option lists). */
export const Card: Story = {
  render: function Render() {
    const [a, setA] = useState(true);
    const [b, setB] = useState(false);
    return (
      <div className="max-w-md space-y-2">
        <Checkbox variant="card" size="sm" label="Include child accounts" description="Roll balances up from nested accounts" checked={a} onChange={setA} />
        <Checkbox variant="card" size="sm" label="Include closed accounts" description="Show accounts closed in the period" checked={b} onChange={setB} />
      </div>
    );
  },
};

/** Label-less checkbox (e.g. table row selection) must carry an aria-label. */
export const RowSelect: Story = {
  render: function Render() {
    const [sel, setSel] = useState<string[]>(['INV-2']);
    const rows = ['INV-1', 'INV-2', 'INV-3'];
    const toggle = (id: string) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
    return (
      // eslint-disable-next-line no-restricted-syntax -- minimal markup to show a label-less row checkbox
      <table className="body-sm">
        <tbody>
          {rows.map((id) => (
            <tr key={id}>
              <td className="pr-4 py-1"><Checkbox size="sm" aria-label={`Select ${id}`} checked={sel.includes(id)} onChange={() => toggle(id)} /></td>
              <td>{id}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  },
};
