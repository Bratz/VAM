// ============================================================================
// FxSettlementPage ("Settlement Instructions") — where each entity's FX deals settle, per currency: one of its
// accounts at the home bank. A standing instruction picks the account; without one, an entity's only account in
// that currency is used, and with several (or none) its deals in that currency are declined until one is chosen
// (or the bank opens one). A deal takes the accounts when it is priced; it can be changed on the deal itself.
// ============================================================================

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Pencil, RefreshCw, Landmark, CheckCircle, AlertTriangle } from 'lucide-react';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Badge, Button, Card, CardHeader, DataTable, RowActions, Select, StatTile } from '../../components/ui';
import { Modal } from '../../components/ui/enhanced';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import { fxDealApi, InstructionRow } from '../../services/fxDealApi';
import { errorText, fmt } from './fxFormat';

const source = (r: InstructionRow): { label: string; variant: 'success' | 'info' | 'warning' | 'error' } =>
  r.resolved.account
    ? r.resolved.fromInstruction ? { label: 'Instruction', variant: 'success' } : { label: 'Only account', variant: 'info' }
    : r.eligible.length > 1 ? { label: 'Choose one', variant: 'warning' } : { label: 'No account', variant: 'error' };

const FxSettlementPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const corporateId = currentCorporateId ?? '';

  const [rows, setRows] = useState<InstructionRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  const [editing, setEditing] = useState<InstructionRow | null>(null);
  const [account, setAccount] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    try {
      setRows(await fxDealApi.instructions(corporateId));
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => {
    setRows([]);
    refresh();
  }, [refresh]);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((r) => !q || r.entityName.toLowerCase().includes(q) || r.currency.toLowerCase().includes(q));
  }, [rows, query]);

  const ready = rows.filter((r) => r.resolved.account).length;
  const blocked = rows.length - ready;

  const edit = (r: InstructionRow) => {
    setEditing(r);
    setAccount(r.resolved.account?.id ?? r.eligible[0]?.id ?? '');
    setSaveError(null);
  };

  const save = async () => {
    if (!editing) return;
    setSaving(true);
    try {
      await fxDealApi.setInstruction(corporateId, editing.entityId, editing.currency, account);
      setEditing(null);
      refresh();
    } catch (err) {
      setSaveError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const columns: Column<InstructionRow>[] = [
    { key: 'entityName', header: 'Entity', render: (_, r) => <span className="font-medium">{r.entityName}</span> },
    { key: 'currency', header: 'Currency', render: (_, r) => <span className="font-mono">{r.currency}</span> },
    {
      key: 'resolved', header: 'Deals settle through', render: (_, r) => r.resolved.account
        ? <span className="text-body-sm">{r.resolved.account.label}</span>
        : <span className="block text-caption text-neutral-600 dark:text-neutral-300 max-w-[28rem]">{r.resolved.reason}</span>,
    },
    {
      key: 'balance', header: 'Balance', align: 'right', dropOrder: 2, render: (_, r) => r.resolved.account
        ? <span className="font-mono tabular-nums">{fmt(r.resolved.account.balance)}</span>
        : <span className="text-neutral-500 dark:text-neutral-400">-</span>,
    },
    { key: 'source', header: 'Set by', dropOrder: 1, render: (_, r) => { const s = source(r); return <Badge variant={s.variant} size="sm">{s.label}</Badge>; } },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, r) => (
        <RowActions actions={[
          { label: r.resolved.fromInstruction ? 'Change account…' : 'Set account…', icon: <Pencil className="w-4 h-4" />, onClick: () => edit(r), hidden: !r.eligible.length },
        ]} />
      ),
    },
  ];

  return (
    <Page>
      <PageHeader
        title="Settlement Instructions"
        description="The account each entity’s FX deals settle through in each currency: the bought currency is credited to it and the sold currency debited. Only accounts at the bank you deal with can be used. Where an entity has one account in a currency it is used; where it has several, choose one, or deals in that currency are declined."
      />

      <StatStrip columns={3}>
        <StatTile tone="primary" icon={<Landmark className="w-5 h-5" />} label="Entity currencies" value={String(rows.length)} loading={loading && !rows.length} />
        <StatTile tone="success" icon={<CheckCircle className="w-5 h-5" />} label="Ready to settle" value={String(ready)} sub="an account is set or is the only one" />
        <StatTile tone={blocked ? 'warning' : 'success'} icon={<AlertTriangle className="w-5 h-5" />} label="Need an account" value={String(blocked)}
          sub={blocked ? 'deals in these are declined' : 'nothing to do'} />
      </StatStrip>

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      <Card>
        <CardHeader title="Instructions" subtitle={`${shown.length} of ${rows.length}`} />
        <DataTable<InstructionRow>
          data={shown}
          columns={columns}
          keyExtractor={(r) => `${r.entityId}|${r.currency}`}
          onRowClick={(r) => r.eligible.length && edit(r)}
          searchable
          searchPlaceholder="Search entity or currency..."
          onSearch={setQuery}
          loading={loading && !rows.length}
          compact
          hairline
          emptyTitle="No accounts at the home bank"
          emptyDescription="None of this corporate’s entities hold an account at the bank yet, so it cannot settle FX deals."
          actions={<Button size="sm" variant="outline" onClick={refresh} leftIcon={<RefreshCw className="w-4 h-4" />}>Refresh</Button>}
        />
      </Card>

      <Modal
        isOpen={!!editing}
        onClose={() => setEditing(null)}
        title={`${editing?.currency ?? ''} settlement account`}
        subtitle={editing?.entityName}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditing(null)}>Cancel</Button>
            <Button onClick={save} loading={saving} disabled={!account}>Save instruction</Button>
          </>
        }
      >
        {editing && (
          <div className="space-y-3">
            <Select label="Account" value={account} onChange={(e) => setAccount(e.target.value)}
              options={editing.eligible.map((a) => ({ value: a.id, label: `${a.label}, balance ${fmt(a.balance)}` }))} />
            <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
              New deals in {editing.currency} use this account. Deals already booked keep theirs; change those on the deal.
            </p>
            {saveError && <p className="text-body-sm text-error-700 dark:text-error-300">{saveError}</p>}
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default FxSettlementPage;
