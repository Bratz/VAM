import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { RadioGroup } from './Radio';

const OPTIONS = [
  { value: 'std', label: 'Standard', description: '2-3 business days' },
  { value: 'fast', label: 'Faster Payments', description: 'Within minutes' },
  { value: 'sameday', label: 'Same day', disabled: true },
];

const meta: Meta<typeof RadioGroup> = {
  title: 'Components/Radio',
  component: RadioGroup,
  tags: ['autodocs'],
  args: { legend: 'Payment speed', options: OPTIONS, size: 'md', orientation: 'vertical', disabled: false },
  argTypes: {
    size: { control: 'select', options: ['sm', 'md'] },
    orientation: { control: 'select', options: ['vertical', 'horizontal'] },
    disabled: { control: 'boolean' },
    error: { control: 'text' },
  },
  render: function Render(args) {
    const [v, setV] = useState('std');
    return <RadioGroup {...args} value={v} onChange={setV} />;
  },
};
export default meta;
type Story = StoryObj<typeof RadioGroup>;

export const Playground: Story = {};
export const Horizontal: Story = { args: { orientation: 'horizontal' } };
export const WithError: Story = { args: { error: 'Choose a speed' } };
export const Disabled: Story = { args: { disabled: true } };
