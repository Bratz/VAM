import type { Meta, StoryObj } from '@storybook/react-vite';
import { ProgressBar } from './enhanced';
import { Progress } from './index';

const meta: Meta<typeof ProgressBar> = {
  title: 'Components/ProgressBar',
  component: ProgressBar,
  tags: ['autodocs'],
  args: { value: 64, max: 100, size: 'md', variant: 'default', label: 'Batch upload', showLabel: true, animated: false },
  argTypes: {
    value: { control: { type: 'range', min: 0, max: 100 } },
    size: { control: 'select', options: ['sm', 'md', 'lg'] },
    variant: { control: 'select', options: ['default', 'success', 'warning', 'error'] },
    showLabel: { control: 'boolean' },
    animated: { control: 'boolean' },
  },
  decorators: [(Story) => <div className="max-w-md"><Story /></div>],
};
export default meta;
type Story = StoryObj<typeof ProgressBar>;

export const Playground: Story = {};

export const VariantsAndSizes: Story = {
  render: () => (
    <div className="space-y-4">
      <ProgressBar value={30} variant="default" label="Facility drawn" showLabel />
      <ProgressBar value={100} variant="success" label="Reconciled" showLabel size="sm" />
      <ProgressBar value={78} variant="warning" label="Limit used" showLabel size="lg" />
      <ProgressBar value={96} variant="error" label="Overdraft" showLabel animated />
    </div>
  ),
};

export const ProgressExport: Story = {
  name: 'Progress (index export)',
  render: () => (
    <div className="space-y-4">
      <Progress value={40} showLabel />
      <Progress value={85} variant="success" size="lg" showLabel />
      <Progress value={60} variant="warning" size="sm" />
      <Progress value={92} variant="error" />
    </div>
  ),
};
