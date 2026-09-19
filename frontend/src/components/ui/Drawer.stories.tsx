import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Drawer } from './Drawer';
import { Button } from './index';

const meta: Meta<typeof Drawer> = {
  title: 'Components/Drawer',
  component: Drawer,
  tags: ['autodocs'],
  parameters: { layout: 'fullscreen' },
  args: { title: 'Payment INV-2026-00412', subtitle: 'Halcyon Logistics Ltd', size: 'lg' },
  argTypes: {
    size: { control: 'radio', options: ['md', 'lg', 'xl'] },
    isOpen: { control: false },
    onClose: { control: false },
    footer: { control: false },
    children: { control: false },
  },
  render: function Render(args) {
    const [open, setOpen] = useState(false);
    return (
      <div className="p-6">
        <Button onClick={() => setOpen(true)}>Open drawer</Button>
        <Drawer
          {...args}
          isOpen={open}
          onClose={() => setOpen(false)}
          footer={
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
              <Button onClick={() => setOpen(false)}>Approve</Button>
            </div>
          }
        >
          <div className="p-6 space-y-4">
            <p className="body-sm">Amount</p>
            <p className="body-strong">GBP 48,250.00</p>
            <p className="body-sm">Beneficiary IBAN</p>
            <p className="body-strong">GB29 NWBK 6016 1331 9268 19</p>
            <p className="body-sm">Due date</p>
            <p className="body-strong">30 Sep 2026</p>
          </div>
        </Drawer>
      </div>
    );
  },
};
export default meta;
type Story = StoryObj<typeof Drawer>;

export const Playground: Story = {};
export const Medium: Story = { args: { size: 'md' } };
export const ExtraLarge: Story = { args: { size: 'xl' } };

export const OpenByDefault: Story = {
  render: (args) => (
    <Drawer {...args} isOpen onClose={() => undefined} footer={<div className="flex justify-end"><Button>Close</Button></div>}>
      <div className="p-6"><p className="body">Static open state for visual review.</p></div>
    </Drawer>
  ),
};
