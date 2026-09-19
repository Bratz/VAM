import type { Meta, StoryObj } from '@storybook/react-vite';
import { Search, Mail } from 'lucide-react';
import { Input, Select, TextArea } from './index';

const meta: Meta<typeof Input> = {
  title: 'Components/Input',
  component: Input,
  tags: ['autodocs'],
  args: { label: 'Account name', placeholder: 'Enter a name', inputSize: 'md' },
  argTypes: {
    inputSize: { control: 'select', options: ['sm', 'md', 'lg'] },
    disabled: { control: 'boolean' },
    error: { control: 'text' },
    hint: { control: 'text' },
  },
  decorators: [(Story) => <div className="max-w-sm"><Story /></div>],
};
export default meta;
type Story = StoryObj<typeof Input>;

export const Playground: Story = {};

export const Sizes: Story = {
  render: () => (
    <div className="space-y-4">
      {(['sm', 'md', 'lg'] as const).map((s) => (
        <Input key={s} label={`Size ${s}`} inputSize={s} placeholder="Placeholder" />
      ))}
    </div>
  ),
};

export const States: Story = {
  render: () => (
    <div className="space-y-4">
      <Input label="With hint" hint="Shown to your counterparties" placeholder="Value" />
      <Input label="Error" error="This field is required" defaultValue="Bad value" />
      <Input label="Success" success defaultValue="Looks good" />
      <Input label="Disabled" disabled defaultValue="Read only" />
    </div>
  ),
};

export const WithIcons: Story = {
  render: () => (
    <div className="space-y-4">
      <Input label="Search" leftIcon={<Search />} placeholder="Search accounts" />
      <Input label="Email" leftIcon={<Mail />} placeholder="name@company.com" />
    </div>
  ),
};

export const SelectField: Story = {
  render: () => (
    <div className="space-y-4">
      {(['sm', 'md', 'lg'] as const).map((s) => (
        <Select
          key={s}
          label={`Select ${s}`}
          selectSize={s}
          placeholder="Choose currency"
          options={[{ value: 'GBP', label: 'GBP' }, { value: 'EUR', label: 'EUR' }, { value: 'USD', label: 'USD', disabled: true }]}
        />
      ))}
      <Select label="Error" error="Pick one" options={[{ value: 'a', label: 'A' }]} />
      <Select label="Disabled" disabled options={[{ value: 'a', label: 'A' }]} />
    </div>
  ),
};

export const TextAreaField: Story = {
  render: () => (
    <div className="space-y-4">
      {(['sm', 'md', 'lg'] as const).map((s) => (
        <TextArea key={s} label={`TextArea ${s}`} textareaSize={s} placeholder="Notes" />
      ))}
      <TextArea label="Error" error="Too short" />
      <TextArea label="Disabled" disabled defaultValue="Locked" />
    </div>
  ),
};
