import { useMemo, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { DataTable, type Column } from './DataTable';
import { Badge, Button } from './index';

interface Invoice {
  id: string;
  supplier: string;
  amount: number;
  currency: string;
  dueDate: string;
  status: 'Paid' | 'Pending' | 'Overdue';
  account: string;
  reference: string;
}

const ROWS: Invoice[] = [
  { id: '1', supplier: 'Halcyon Logistics Ltd', amount: 48250.0, currency: 'GBP', dueDate: '2026-09-30', status: 'Pending', account: 'GB29 NWBK 6016 1331 9268 19', reference: 'INV-2026-00412' },
  { id: '2', supplier: 'Northwind Traders', amount: 12980.5, currency: 'USD', dueDate: '2026-09-12', status: 'Overdue', account: 'GB33 BUKB 2020 1555 5555 55', reference: 'INV-2026-00413' },
  { id: '3', supplier: 'Bramley Facilities', amount: 3120.0, currency: 'GBP', dueDate: '2026-10-04', status: 'Pending', account: 'GB94 BARC 1020 1530 0933 47', reference: 'INV-2026-00414' },
  { id: '4', supplier: 'Kestrel Software', amount: 76400.0, currency: 'USD', dueDate: '2026-09-02', status: 'Paid', account: 'GB82 WEST 1234 5698 7654 32', reference: 'INV-2026-00415' },
  { id: '5', supplier: 'Marlow & Finch LLP', amount: 9875.25, currency: 'GBP', dueDate: '2026-10-15', status: 'Pending', account: 'GB15 MIDL 4005 1573 6942 11', reference: 'INV-2026-00416' },
  { id: '6', supplier: 'Tidewater Energy', amount: 214300.0, currency: 'USD', dueDate: '2026-09-25', status: 'Pending', account: 'GB60 NWBK 6016 1331 9268 20', reference: 'INV-2026-00417' },
  { id: '7', supplier: 'Ashdown Packaging', amount: 5540.8, currency: 'GBP', dueDate: '2026-08-28', status: 'Overdue', account: 'GB47 HBUK 4003 2710 4712 08', reference: 'INV-2026-00418' },
  { id: '8', supplier: 'Corvid Analytics', amount: 18200.0, currency: 'USD', dueDate: '2026-09-18', status: 'Paid', account: 'GB71 LOYD 3096 2900 1122 33', reference: 'INV-2026-00419' },
  { id: '9', supplier: 'Wexford Insurance', amount: 32760.4, currency: 'GBP', dueDate: '2026-11-01', status: 'Pending', account: 'GB18 CPBK 0800 4000 5566 77', reference: 'INV-2026-00420' },
  { id: '10', supplier: 'Larkspur Catering', amount: 1499.99, currency: 'GBP', dueDate: '2026-09-21', status: 'Paid', account: 'GB05 SRLG 6090 7100 2233 44', reference: 'INV-2026-00421' },
];

const TONE = { Paid: 'success', Pending: 'warning', Overdue: 'error' } as const;

const COLUMNS: Column<Invoice>[] = [
  { key: 'reference', header: 'Invoice', sortable: true, minWidth: 140 },
  { key: 'supplier', header: 'Supplier', sortable: true, minWidth: 200 },
  { key: 'amount', header: 'Amount', sortable: true, align: 'right', minWidth: 150, render: (_v, r) => `${r.currency} ${r.amount.toLocaleString('en-GB', { minimumFractionDigits: 2 })}` },
  { key: 'status', header: 'Status', minWidth: 120, render: (_v, r) => <Badge variant={TONE[r.status]} size="sm">{r.status}</Badge> },
  { key: 'dueDate', header: 'Due date', sortable: true, minWidth: 130, dropOrder: 1 },
  { key: 'account', header: 'Beneficiary IBAN', minWidth: 240, dropOrder: 2 },
  { key: 'id', header: 'Ledger ID', minWidth: 110, dropOrder: 3 },
];

const meta: Meta<typeof DataTable<Invoice>> = {
  title: 'Components/DataTable',
  component: DataTable,
  tags: ['autodocs'],
  args: { data: ROWS, columns: COLUMNS, keyExtractor: (r: Invoice) => r.id },
  argTypes: {
    loading: { control: 'boolean' },
    selectable: { control: 'boolean' },
    striped: { control: 'boolean' },
    compact: { control: 'boolean' },
    hairline: { control: 'boolean' },
    stickyHeader: { control: 'boolean' },
    pagination: { control: 'boolean' },
    data: { control: false },
    columns: { control: false },
  },
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof DataTable<Invoice>>;

export const Playground: Story = {};

export const Loading: Story = { args: { loading: true, pageSize: 5 } };

export const Empty: Story = { args: { data: [], emptyTitle: 'No invoices', emptyDescription: 'No invoices match the current filters.' } };

export const Sortable: Story = {
  render: function Render(args) {
    const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' }>({ key: 'amount', dir: 'desc' });
    const data = useMemo(() => {
      const k = sort.key as keyof Invoice;
      return [...ROWS].sort((a, b) => (a[k] > b[k] ? 1 : a[k] < b[k] ? -1 : 0) * (sort.dir === 'asc' ? 1 : -1));
    }, [sort]);
    return <DataTable {...args} data={data} sortKey={sort.key} sortDirection={sort.dir} onSort={(key, dir) => setSort({ key, dir })} />;
  },
};

export const SelectableWithBulkActions: Story = {
  render: function Render(args) {
    const [sel, setSel] = useState<Set<string | number>>(new Set(['2']));
    return (
      <DataTable
        {...args}
        selectable
        selectedKeys={sel}
        onSelectionChange={setSel}
        bulkActions={<Button size="sm" variant="outline">Approve</Button>}
      />
    );
  },
};

export const Paginated: Story = {
  render: function Render(args) {
    const [page, setPage] = useState(1);
    const size = 4;
    return (
      <DataTable
        {...args}
        data={ROWS.slice((page - 1) * size, page * size)}
        totalCount={ROWS.length}
        pagination
        pageSize={size}
        currentPage={page}
        onPageChange={setPage}
      />
    );
  },
};

export const ResponsiveColumnDropping: Story = {
  parameters: { docs: { description: { story: 'Columns with `dropOrder` are hidden (highest first) when the container is too narrow for their summed `minWidth`. Drag the slider.' } } },
  render: function Render(args) {
    const [w, setW] = useState(1100);
    return (
      <div className="space-y-4">
        <label className="flex items-center gap-3 body-sm">
          Container width: {w}px
          <input type="range" min={480} max={1400} step={10} value={w} onChange={(e) => setW(Number(e.target.value))} />
        </label>
        <div style={{ width: w, maxWidth: '100%' }}>
          <DataTable {...args} selectable />
        </div>
      </div>
    );
  },
};

export const FixedWidthContainers: Story = {
  render: (args) => (
    <div className="space-y-6">
      {[560, 860, 1200].map((w) => (
        <div key={w}>
          <p className="caption mb-2">{w}px</p>
          <div style={{ width: w, maxWidth: '100%' }}><DataTable {...args} /></div>
        </div>
      ))}
    </div>
  ),
};
