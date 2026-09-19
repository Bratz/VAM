import type { Meta, StoryObj } from '@storybook/react-vite';
import { Alert } from './enhanced';

const VARIANTS = ['info', 'success', 'warning', 'error', 'danger'] as const;

const meta: Meta<typeof Alert> = {
  title: 'Components/Alert',
  component: Alert,
  tags: ['autodocs'],
  args: { variant: 'info', title: 'Statement available', children: 'The 31 August 2026 statement for GBP account 20-45-67 is ready to download.' },
  argTypes: { variant: { control: 'select', options: VARIANTS }, onClose: { control: false } },
};
export default meta;
type Story = StoryObj<typeof Alert>;

export const Playground: Story = {};

export const AllVariants: Story = {
  render: () => (
    <div className="space-y-3 max-w-xl">
      <Alert variant="info" title="Cut-off reminder">Same-day GBP payments must be released before 15:30.</Alert>
      <Alert variant="success" title="Payment released">Invoice INV-2026-00412 for GBP 48,250.00 was sent.</Alert>
      <Alert variant="warning" title="Low balance">USD operating account is below the GBP 25,000 buffer.</Alert>
      <Alert variant="error" title="Payment rejected">Beneficiary IBAN failed validation.</Alert>
      <Alert variant="danger">Sanctions screening hit requires review.</Alert>
    </div>
  ),
};

export const Dismissible: Story = { args: { variant: 'warning', onClose: () => undefined } };
