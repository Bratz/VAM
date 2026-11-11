import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, Plus, XCircle } from 'lucide-react';
import { Badge, Button, DataTable, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { legalEntityApi, poboEnrolmentApi, type LegalEntity, type PoboCutoverItem, type PoboCutoverReport, type PoboEnrolment, type PoboPaymentAccount } from '../../services/api';
import { formatCurrency, formatDate } from '../../utils';

/**
 * Payments on behalf (POBO) of the selected corporate: which subsidiary, per currency, the treasury centre pays for
 * and from which bank-backed account (spec docs/specs/pobo-in-house-bank.md R1-R3). Approved payables of an enrolled
 * subsidiary in that currency are paid by treasury and booked to the subsidiary's IHB current account.
 */
export const PoboEnrolmentsPanel: React.FC<{
  corporateId: string;
  /** The corporate's IHB current accounts: a subsidiary can be enrolled in the currencies it has one in. */
  currentAccounts: { participantEntityId: string; currencyCode: string }[];
}> = ({ corporateId, currentAccounts }) => {
  const [enrolments, setEnrolments] = useState<PoboEnrolment[]>([]);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // Preview of the cut-over and repair (spec D8/D9); re-run after every change to the enrolments.
  const [cutover, setCutover] = useState<PoboCutoverReport | null>(null);
  const [cutoverOpen, setCutoverOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, ents] = await Promise.all([
        poboEnrolmentApi.list(corporateId),
        legalEntityApi.getByCorporate(corporateId).then(r => (Array.isArray(r?.data) ? r.data : [])),
      ]);
      setEnrolments(list);
      setEntities(ents);
      setCutover(await poboEnrolmentApi.cutover(corporateId, true).catch(() => null));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load the enrolments');
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { load(); }, [load]);

  const end = async (e: PoboEnrolment) => {
    if (!window.confirm(`End payment on behalf of ${e.subsidiaryName ?? e.subsidiaryCode} in ${e.currency}? Its payables will be paid directly from its own accounts from now on.`)) return;
    try {
      await poboEnrolmentApi.end(e.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not end the enrolment');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center gap-4">
        <p className="body-sm">
          Approved payables of an enrolled subsidiary in that currency are paid by the treasury centre from its account and
          booked to the subsidiary's IHB current account, within its credit limit. No fee is charged.
        </p>
        <div className="flex gap-2 shrink-0">
          {cutover && changes(cutover) > 0 && (
            <Button size="sm" variant="outline" leftIcon={<ArrowRightLeft className="w-4 h-4" />} onClick={() => setCutoverOpen(true)}>
              Move existing POBO payables and repair ({changes(cutover)})
            </Button>
          )}
          <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setAdding(true)}>
            Add enrolment
          </Button>
        </div>
      </div>
      {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      <DataTable
        data={enrolments}
        loading={loading}
        keyExtractor={(e) => e.id}
        emptyTitle="No subsidiary is paid on behalf"
        columns={[
          { key: 'subsidiaryName', header: 'Subsidiary', render: (_, e) => (
            <div className="flex flex-col"><span className="body-strong">{e.subsidiaryName ?? '-'}</span><span className="caption">{e.subsidiaryCode}</span></div>
          ) },
          { key: 'currency', header: 'Currency', minWidth: 90 },
          { key: 'payerName', header: 'Paid by', render: (_, e) => e.payerName ?? e.payerCode ?? '-' },
          { key: 'paymentVaNumber', header: 'From account', render: (_, e) => (
            <div className="flex flex-col"><span className="font-mono text-body-sm">{e.paymentVaNumber ?? '-'}</span><span className="caption font-mono">{e.paymentIban ?? ''}</span></div>
          ) },
          { key: 'headroom', header: 'IHB headroom', align: 'right', minWidth: 130, render: (_, e) => (e.headroom != null ? formatCurrency(e.headroom, e.currency) : '-') },
          { key: 'effectiveFrom', header: 'Effective', render: (_, e) => `${formatDate(e.effectiveFrom)}${e.effectiveTo ? ' – ' + formatDate(e.effectiveTo) : ''}` },
          { key: 'active', header: 'Status', minWidth: 100, render: (_, e) => (
            <Badge variant={e.active ? 'success' : 'neutral'} size="sm">{e.active ? 'Active' : e.effectiveTo ? 'Ended' : 'Scheduled'}</Badge>
          ) },
          { key: 'actions', header: '', align: 'right', minWidth: 90, render: (_, e) => (!e.effectiveTo || new Date(e.effectiveTo) > new Date()) && (
            <Button size="xs" variant="ghost" leftIcon={<XCircle className="w-4 h-4" />} onClick={() => end(e)}>End</Button>
          ) },
        ]}
      />
      {adding && (
        <AddEnrolmentModal
          corporateId={corporateId}
          entities={entities}
          currentAccounts={currentAccounts}
          onClose={() => setAdding(false)}
          onSaved={() => { setAdding(false); load(); }}
        />
      )}
      {cutoverOpen && cutover && (
        <CutoverModal
          corporateId={corporateId}
          preview={cutover}
          onClose={() => { setCutoverOpen(false); load(); }}
        />
      )}
    </div>
  );
};

/** What a real run would change; past self-sweeps are information only. */
const changes = (r: PoboCutoverReport) =>
  r.payables.length + r.mirrors.length + r.recharges.length + r.creditLimits.length + r.sweepRules.length;

/** Preview of the cut-over and repair, then the real run and what it did. */
const CutoverModal: React.FC<{ corporateId: string; preview: PoboCutoverReport; onClose: () => void }> = ({ corporateId, preview, onClose }) => {
  const [report, setReport] = useState(preview);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const count = changes(report);

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setReport(await poboEnrolmentApi.cutover(corporateId, false));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not run the cut-over');
    } finally {
      setRunning(false);
    }
  };

  const section = (title: string, items: PoboCutoverItem[], variant: 'info' | 'success' | 'neutral' | 'warning', info?: string) => (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="body-strong">{title}</span>
        <Badge variant={variant} size="sm">{items.length}</Badge>
      </div>
      {info && <p className="caption">{info}</p>}
      {items.length > 0 && (
        <DataTable
          data={items}
          keyExtractor={(i) => i.key}
          columns={[
            { key: 'subject', header: 'Item', render: (_, i) => (
              <div className="flex flex-col"><span className="font-mono text-body-sm">{i.subject}</span><span className="caption">{i.sub ?? ''}</span></div>
            ) },
            { key: 'amount', header: 'Amount', align: 'right', minWidth: 130, render: (_, i) => (i.amount != null && i.currency ? formatCurrency(i.amount, i.currency) : '-') },
            { key: 'detail', header: info ? 'What we found' : report.dryRun ? 'What happens' : 'What happened', render: (_, i) => <span className="body-sm">{i.detail}</span> },
          ]}
        />
      )}
    </div>
  );

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={report.dryRun ? 'Move existing POBO payables and repair' : 'POBO cut-over done'}
      subtitle={report.dryRun
        ? 'Payables from the old request-and-approval flow, and the in-house balances that drifted under it. Nothing changes until you confirm.'
        : 'Done. Moved payables pay normally: on behalf if their owner is enrolled for the currency on the payment date, else directly.'}
      size="xl"
      footer={
        <div className="flex justify-end gap-2">
          {report.dryRun ? (
            <>
              <Button variant="ghost" onClick={onClose}>Cancel</Button>
              <Button onClick={run} loading={running} disabled={count === 0}>
                Apply {count} change{count === 1 ? '' : 's'}
              </Button>
            </>
          ) : (
            <Button onClick={onClose}>Close</Button>
          )}
        </div>
      }
    >
      <div className="space-y-6">
        {section('Payables back to Approved', report.payables, 'success')}
        {section("Treasury's mirrors set from each IHB position", report.mirrors, 'info')}
        {section('POBO recharges closed as settled by the IHB position', report.recharges, 'info')}
        {section('IHB credit use set from the balance', report.creditLimits, 'info')}
        {section('Sweep rules on in-house accounts stopped', report.sweepRules, 'info')}
        {section('Past self-sweeps', report.pastSelfSweeps, 'warning',
          'Information only, nothing is changed: these sweeps added cash to a treasury account that the IHB current account got straight back. Decide whether to correct them.')}
        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      </div>
    </Modal>
  );
};

const AddEnrolmentModal: React.FC<{
  corporateId: string;
  entities: LegalEntity[];
  currentAccounts: { participantEntityId: string; currencyCode: string }[];
  onClose: () => void;
  onSaved: () => void;
}> = ({ corporateId, entities, currentAccounts, onClose, onSaved }) => {
  const payers = entities.filter(e => e.isTreasuryCenter);
  const [subsidiaryId, setSubsidiaryId] = useState('');
  const [currency, setCurrency] = useState('');
  const [payerId, setPayerId] = useState(payers.length === 1 ? payers[0].id : '');
  const [accounts, setAccounts] = useState<PoboPaymentAccount[]>([]);
  const [accountId, setAccountId] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Payments are booked in-house only in a currency the subsidiary has an IHB current account in.
  const currencies = useMemo(
    () => [...new Set(currentAccounts.filter(a => a.participantEntityId === subsidiaryId).map(a => a.currencyCode))].sort(),
    [currentAccounts, subsidiaryId]);
  useEffect(() => { setCurrency(currencies.length === 1 ? currencies[0] : ''); }, [currencies]);

  useEffect(() => {
    setAccounts([]);
    setAccountId('');
    if (!payerId || !currency) return;
    let live = true;
    poboEnrolmentApi.paymentAccounts(payerId, currency)
      .then(list => { if (live) { setAccounts(list); setAccountId(list.length === 1 ? list[0].id : ''); } })
      .catch(() => { if (live) setAccounts([]); });
    return () => { live = false; };
  }, [payerId, currency]);

  const subsidiary = entities.find(e => e.id === subsidiaryId);
  const payer = entities.find(e => e.id === payerId);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await poboEnrolmentApi.create({ corporateId, subsidiaryEntityId: subsidiaryId, currency, payerEntityId: payerId, paymentVaId: accountId });
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not enrol');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title="Pay on behalf of a subsidiary"
      size="md"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button onClick={save} loading={saving} disabled={!subsidiaryId || !currency || !payerId || !accountId}>Enrol</Button>
        </div>
      }
    >
      <div className="space-y-4">
        <Select label="Subsidiary" value={subsidiaryId} onChange={e => setSubsidiaryId(e.target.value)}
          placeholder="Choose the subsidiary"
          options={entities.filter(e => !e.isTreasuryCenter).map(e => ({ value: e.id, label: `${e.entityName} (${e.entityCode})` }))} />
        <Select label="Currency" value={currency} onChange={e => setCurrency(e.target.value)}
          placeholder={subsidiaryId ? 'Choose the currency' : 'Choose the subsidiary first'}
          disabled={!subsidiaryId || currencies.length === 0}
          error={subsidiary && currencies.length === 0
            ? `${subsidiary.entityCode} has no IHB current account; open one under Current Accounts first` : undefined}
          options={currencies.map(c => ({ value: c, label: c }))} />
        <Select label="Paid by (treasury centre)" value={payerId} onChange={e => setPayerId(e.target.value)}
          placeholder="Choose the treasury centre"
          error={payers.length === 0 ? 'No entity of this corporate is flagged as the treasury centre (Legal Entities)' : undefined}
          options={payers.map(e => ({ value: e.id, label: `${e.entityName} (${e.entityCode})` }))} />
        <Select label="Payment account" value={accountId} onChange={e => setAccountId(e.target.value)}
          placeholder={payerId && currency ? 'Choose the account suppliers are paid from' : 'Choose the currency and treasury centre first'}
          disabled={!payerId || !currency || accounts.length === 0}
          error={payer && currency && accounts.length === 0
            ? `${payer.entityCode} has no active ${currency} account backed by a bank account (IBAN) to pay from` : undefined}
          options={accounts.map(a => ({ value: a.id, label: `${a.vaNumber}${a.vaName ? ' · ' + a.vaName : ''} · ${a.iban}` }))} />
        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      </div>
    </Modal>
  );
};
