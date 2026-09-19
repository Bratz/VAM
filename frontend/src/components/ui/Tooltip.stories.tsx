import type { Meta, StoryObj } from '@storybook/react-vite';
import { Tooltip } from './enhanced';
import { Button } from './index';

const meta: Meta<typeof Tooltip> = {
  title: 'Components/Tooltip',
  component: Tooltip,
  tags: ['autodocs'],
  args: { content: 'GBP 14,437,919.42', position: 'top', delay: 200, children: <Button variant="outline">Hover me</Button> },
  argTypes: {
    position: { control: 'select', options: ['top', 'bottom', 'left', 'right'] },
    delay: { control: { type: 'number', min: 0, max: 1500 } },
    children: { control: false },
  },
  decorators: [(Story) => <div className="flex justify-center py-16"><Story /></div>],
};
export default meta;
type Story = StoryObj<typeof Tooltip>;

export const Playground: Story = {};

export const Positions: Story = {
  render: () => (
    <div className="flex gap-6">
      {(['top', 'bottom', 'left', 'right'] as const).map((p) => (
        <Tooltip key={p} content={`Full precision, ${p}`} position={p}>
          <Button variant="outline" size="sm">{p}</Button>
        </Tooltip>
      ))}
    </div>
  ),
};
