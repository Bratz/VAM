import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Modal } from './enhanced';
import { Button } from './index';

const meta: Meta<typeof Modal> = {
  title: 'Components/Modal',
  component: Modal,
  tags: ['autodocs'],
  parameters: { layout: 'fullscreen' },
  argTypes: { size: { control: 'select', options: ['sm', 'md', 'lg', 'xl', 'full'] } },
};
export default meta;
type Story = StoryObj<typeof Modal>;

const Demo = ({ size }: { size?: 'sm' | 'md' | 'lg' | 'xl' | 'full' }) => {
  const [open, setOpen] = useState(true);
  return (
    <div className="p-6">
      <Button onClick={() => setOpen(true)}>Open modal</Button>
      <Modal
        isOpen={open}
        onClose={() => setOpen(false)}
        title="Confirm transfer"
        subtitle="Review the details below"
        size={size}
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={() => setOpen(false)}>Confirm</Button>
          </>
        }
      >
        <p className="body-sm">Move GBP 10,000.00 from Operating to Reserve account.</p>
      </Modal>
    </div>
  );
};

export const Default: Story = { render: (args) => <Demo size={args.size} /> };
export const Large: Story = { render: () => <Demo size="lg" /> };
