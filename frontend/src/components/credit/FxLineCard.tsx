// ============================================================================
// FxLineCard — the corporate's FX line on the Credit Limits page. The bank sanctions an FX treasury line (a facility
// under its credit agreement); the corporate's treasury splits it into sub-limits per legal entity. A deal is booked
// for one entity and must fit its sub-limit and the group line; an entity with no sub-limit cannot deal. Sub-limits
// together may not exceed the line, nor be cut below what an entity's open deals already use (the server says so).
// ============================================================================

import React, { useCallback, useEffect, useState } from 'react';
import { ArrowLeftRight, Pencil } from 'lucide-react';
import toast from 'react-hot-toast';
import { Badge, Button, Card, CardHeader, DataTable, Input, RowActions, StatusIconBadge } from '../ui';
import type { Column } from '../ui/DataTable';
import { Modal } from '../ui/enhanced';
import { fxDealApi, FxAllocation, FxLineView } from '../../services/fxDealApi';

const fmt = (n: number) => n.toLocaleString('en-US', { maximumFractionDigits: 0 });
const pct = (used: number, limit: number) => (limit > 0 ? Math.round((used / limit) * 100) : 0);

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || 'Request failed';
};

const FxLineCard: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [view, setView] = useState<FxLineView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<FxAllocation | null>(null);
  const [amount, setAmount] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!corporateId) return;
    try {
      setView(await fxDealApi.line(corporateId));
      setError(null);
    } catch (err) {
      setError(errorText(err));
    }
  }, [corporateId]);

  useEffect(() => { setView(null); load(); }, [load]);

  const save = async () => {
    if (!editing) return;
    const n = Number(amount.replace(/,/g, ''));
    if (!(n >= 0)) { setSaveError('Enter an amount of zero or more.'); return; }
    setSaving(true);
    setSaveError(null);
    try {
      await fxDealApi.allocate(corporateId, editing.entityId, n);
      toast.success(`FX sub-limit for ${editing.entityName} set`);
      setEditing(null);
      load();
    } catch (err) {
      setSaveError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const line = view?.line;
  const ccy = line?.currency ?? '';

  const columns: Column<FxAllocation>[] = [
    { key: 'entityName', header: 'Entity', render: (_, a) => <span className="font-medium">{a.entityName}</span> },
    {
      key: 'limit', header: 'FX sub-limit', align: 'right',
      render: (_, a) => a.allocated
        ? <span className="font-mono tabular-nums">{fmt(a.limit)} {ccy}</span>
        : <span className="text-neutral-500 dark:text-neutral-400">None</span>,
    },
    { key: 'used', header: 'Used by open deals', align: 'right', dropOrder: 2, render: (_, a) => <span className="font-mono tabular-nums">{a.allocated ? `${fmt(a.used)} (${pct(a.used, a.limit)}%)` : '-'}</span> },
    { key: 'headroom', header: 'Left', align: 'right', dropOrder: 1, render: (_, a) => <span className="font-mono tabular-nums">{a.allocated ? fmt(a.headroom) : '-'}</span> },
    {
      key: 'usable', header: 'Can deal',
      render: (_, a) => a.usable
        ? <Badge variant={pct(a.used, a.limit) >= 95 ? 'error' : pct(a.used, a.limit) >= 80 ? 'warning' : 'success'} size="sm">Yes</Badge>
        : <Badge variant="neutral" size="sm">{a.allocated ? 'Blocked' : 'No sub-limit'}</Badge>,
    },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, a) => (
        <RowActions actions={[
          {
            label: a.allocated ? 'Change FX sub-limit' : 'Allocate FX sub-limit', icon: <Pencil className="w-4 h-4" />,
            onClick: () => { setEditing(a); setAmount(a.allocated ? String(a.limit) : ''); setSaveError(null); },
            hidden: !line || !line.facilityName,
          },
        ]} />
      ),
    },
  ];

  return (
    <Card>
      <CardHeader
        title="FX line"
        subtitle={!line ? 'Loading' : !line.facilityName ? line.reason
          : `${line.facilityName}, ${fmt(line.limit)} ${ccy}${line.expiry ? `, until ${line.expiry}` : ''}. Each entity deals within its own sub-limit.`}
        action={<StatusIconBadge tone="accent" icon={ArrowLeftRight} />}
      />
      {error && <p className="text-body-sm text-error-700 dark:text-error-300 mb-3">{error}</p>}
      {line && line.facilityName && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4 text-body-sm">
          <div><p className="caption">Used by open deals</p><p className="font-mono tabular-nums">{fmt(line.used)} {ccy} ({pct(line.used, line.limit)}%)</p></div>
          <div><p className="caption">Left on the line</p><p className="font-mono tabular-nums">{fmt(line.headroom)} {ccy}</p></div>
          <div><p className="caption">Allocated to entities</p><p className="font-mono tabular-nums">{fmt(view?.allocated ?? 0)} {ccy}</p></div>
          <div><p className="caption">Not yet allocated</p><p className="font-mono tabular-nums">{fmt(view?.unallocated ?? 0)} {ccy}</p></div>
        </div>
      )}
      {line && !line.usable && line.facilityName && (
        <p className="text-body-sm text-warning-700 dark:text-warning-300 mb-3">{line.reason}. No entity can deal until it is resolved.</p>
      )}
      <DataTable<FxAllocation>
        data={view?.allocations ?? []}
        columns={columns}
        keyExtractor={(a) => a.entityId}
        compact
        hairline
        loading={!view && !error}
        emptyTitle="No entities"
        emptyDescription="The corporate has no active legal entities."
      />

      <Modal
        isOpen={!!editing}
        onClose={() => setEditing(null)}
        title="FX sub-limit"
        subtitle={editing?.entityName}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditing(null)}>Cancel</Button>
            <Button onClick={save} loading={saving}>Save</Button>
          </>
        }
      >
        <div className="space-y-3">
          <Input label={`Sub-limit (${ccy})`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
            hint={view ? `Up to ${fmt((view.unallocated ?? 0) + (editing?.allocated ? editing.limit : 0))} ${ccy} is free on the line` : undefined} />
          <p className="text-caption text-neutral-500 dark:text-neutral-400">
            Spot deals use their full amount; forwards use a share that grows with time to maturity. Zero stops the entity from dealing.
          </p>
          {saveError && <p className="text-body-sm text-error-700 dark:text-error-300">{saveError}</p>}
        </div>
      </Modal>
    </Card>
  );
};

export default FxLineCard;
