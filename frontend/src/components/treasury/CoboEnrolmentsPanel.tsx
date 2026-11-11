import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, Plus, XCircle } from 'lucide-react';
import { Badge, Button, DataTable, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { coboEnrolmentApi, legalEntityApi, type CoboCollectionAccount, type CoboCutoverItem, type CoboCutoverReport, type CoboEnrolment, type LegalEntity } from '../../services/api';
import { formatCurrency, formatDate } from '../../utils';

/**
 * Collections on behalf (COBO) of the selected corporate: which subsidiary, per currency, the treasury centre
 * collects for and into which account (spec docs/specs/cobo-in-house-bank.md R1-R4). Invoices of an enrolled
 * subsidiary in that currency are collected by treasury from creation; ending an enrolment affects new invoices only.
 */
export const CoboEnrolmentsPanel: React.FC<{
  corporateId: string;
  /** The corporate's IHB current accounts: a subsidiary can be enrolled in the currencies it has one in. */
  currentAccounts: { participantEntityId: string; currencyCode: string }[];
}> = ({ corporateId, currentAccounts }) => {
  const [enrolments, setEnrolments] = useState<CoboEnrolment[]>([]);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // Preview of the cut-over of old-flow COBO invoices (spec D7); re-run after every change to the enrolments.
  const [cutover, setCutover] = useState<CoboCutoverReport | null>(null);
  const [cutoverOpen, setCutoverOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, ents] = await Promise.all([
        coboEnrolmentApi.list(corporateId),
        legalEntityApi.getByCorporate(corporateId).then(r => (Array.isArray(r?.data) ? r.data : [])),
      ]);
      setEnrolments(list);
      setEntities(ents);
      setCutover(await coboEnrolmentApi.cutover(corporateId, true).catch(() => null));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load the enrolments');
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { load(); }, [load]);

  const end = async (e: CoboEnrolment) => {
    if (!window.confirm(`End collection on behalf of ${e.subsidiaryName ?? e.subsidiaryCode} in ${e.currency}? New invoices will be collected directly; existing ones are unchanged.`)) return;
    try {
      await coboEnrolmentApi.end(e.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not end the enrolment');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center gap-4">
        <p className="body-sm">
          Invoices of an enrolled subsidiary in that currency are collected by the treasury centre and credited to the
          subsidiary's IHB current account when paid.
        </p>
        <div className="flex gap-2 shrink-0">
          {cutover && cutover.toDirect.length + cutover.toNewFlow.length > 0 && (
            <Button size="sm" variant="outline" leftIcon={<ArrowRightLeft className="w-4 h-4" />} onClick={() => setCutoverOpen(true)}>
              Move existing COBO invoices ({cutover.toDirect.length + cutover.toNewFlow.length})
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
        emptyTitle="No subsidiary is collected on behalf"
        columns={[
          { key: 'subsidiaryName', header: 'Subsidiary', render: (_, e) => (
            <div className="flex flex-col"><span className="body-strong">{e.subsidiaryName ?? '-'}</span><span className="caption">{e.subsidiaryCode}</span></div>
          ) },
          { key: 'currency', header: 'Currency', minWidth: 90 },
          { key: 'collectorName', header: 'Collected by', render: (_, e) => e.collectorName ?? e.collectorCode ?? '-' },
          { key: 'collectionVaNumber', header: 'Into account', render: (_, e) => (
            <div className="flex flex-col"><span className="font-mono text-body-sm">{e.collectionVaNumber ?? '-'}</span><span className="caption">{e.collectionVaName ?? ''}</span></div>
          ) },
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

const AddEnrolmentModal: React.FC<{
  corporateId: string;
  entities: LegalEntity[];
  currentAccounts: { participantEntityId: string; currencyCode: string }[];
  onClose: () => void;
  onSaved: () => void;
}> = ({ corporateId, entities, currentAccounts, onClose, onSaved }) => {
  const collectors = entities.filter(e => e.isTreasuryCenter);
  const [subsidiaryId, setSubsidiaryId] = useState('');
  const [currency, setCurrency] = useState('');
  const [collectorId, setCollectorId] = useState(collectors.length === 1 ? collectors[0].id : '');
  const [accounts, setAccounts] = useState<CoboCollectionAccount[]>([]);
  const [accountId, setAccountId] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A subsidiary can be credited in-house only in a currency it has an IHB current account in.
  const currencies = useMemo(
    () => [...new Set(currentAccounts.filter(a => a.participantEntityId === subsidiaryId).map(a => a.currencyCode))].sort(),
    [currentAccounts, subsidiaryId]);
  useEffect(() => { setCurrency(currencies.length === 1 ? currencies[0] : ''); }, [currencies]);

  useEffect(() => {
    setAccounts([]);
    setAccountId('');
    if (!collectorId || !currency) return;
    let live = true;
    coboEnrolmentApi.collectionAccounts(collectorId, currency)
      .then(list => { if (live) { setAccounts(list); setAccountId(list.length === 1 ? list[0].id : ''); } })
      .catch(() => { if (live) setAccounts([]); });
    return () => { live = false; };
  }, [collectorId, currency]);

  const subsidiary = entities.find(e => e.id === subsidiaryId);
  const collector = entities.find(e => e.id === collectorId);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await coboEnrolmentApi.create({ corporateId, subsidiaryEntityId: subsidiaryId, currency, collectorEntityId: collectorId, collectionVaId: accountId });
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
      title="Collect on behalf of a subsidiary"
      size="md"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button onClick={save} loading={saving} disabled={!subsidiaryId || !currency || !collectorId || !accountId}>Enrol</Button>
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
        <Select label="Collected by (treasury centre)" value={collectorId} onChange={e => setCollectorId(e.target.value)}
          placeholder="Choose the treasury centre"
          error={collectors.length === 0 ? 'No entity of this corporate is flagged as the treasury centre (Legal Entities)' : undefined}
          options={collectors.map(e => ({ value: e.id, label: `${e.entityName} (${e.entityCode})` }))} />
        <Select label="Collection account" value={accountId} onChange={e => setAccountId(e.target.value)}
          placeholder={collectorId && currency ? 'Choose the account customers pay into' : 'Choose the currency and treasury centre first'}
          disabled={!collectorId || !currency || accounts.length === 0}
          error={collector && currency && accounts.length === 0
            ? `${collector.entityCode} has no active ${currency} collection account to collect into` : undefined}
          options={accounts.map(a => ({ value: a.id, label: `${a.vaNumber}${a.vaName ? ' · ' + a.vaName : ''}` }))} />
        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      </div>
    </Modal>
  );
};

/** Preview of the cut-over, then the real run and what it did. */
const CutoverModal: React.FC<{ corporateId: string; preview: CoboCutoverReport; onClose: () => void }> = ({ corporateId, preview, onClose }) => {
  const [report, setReport] = useState(preview);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setReport(await coboEnrolmentApi.cutover(corporateId, false));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not move the invoices');
    } finally {
      setRunning(false);
    }
  };

  const section = (title: string, items: CoboCutoverItem[], variant: 'info' | 'success' | 'neutral') => (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="body-strong">{title}</span>
        <Badge variant={variant} size="sm">{items.length}</Badge>
      </div>
      {items.length > 0 && (
        <DataTable
          data={items}
          keyExtractor={(i) => i.invoiceId}
          columns={[
            { key: 'invoiceNumber', header: 'Invoice', render: (_, i) => (
              <div className="flex flex-col"><span className="font-mono text-body-sm">{i.invoiceNumber}</span><span className="caption">{i.owningEntity ?? 'No owning entity'}</span></div>
            ) },
            { key: 'outstanding', header: 'Outstanding', align: 'right', minWidth: 130, render: (_, i) => formatCurrency(i.outstanding, i.currency) },
            { key: 'detail', header: report.dryRun ? 'What happens' : 'What happened', render: (_, i) => <span className="body-sm">{i.detail}</span> },
          ]}
        />
      )}
    </div>
  );

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={report.dryRun ? 'Move existing COBO invoices' : 'COBO invoices moved'}
      subtitle={report.dryRun
        ? 'Invoices from the old request-and-approval flow. Nothing changes until you confirm.'
        : 'Done. Customers of moved invoices need the new pay details: resend the invoice or Request to Pay.'}
      size="xl"
      footer={
        <div className="flex justify-end gap-2">
          {report.dryRun ? (
            <>
              <Button variant="ghost" onClick={onClose}>Cancel</Button>
              <Button onClick={run} loading={running} disabled={report.toDirect.length + report.toNewFlow.length === 0}>
                Move {report.toDirect.length + report.toNewFlow.length} invoice{report.toDirect.length + report.toNewFlow.length === 1 ? '' : 's'}
              </Button>
            </>
          ) : (
            <Button onClick={onClose}>Close</Button>
          )}
        </div>
      }
    >
      <div className="space-y-6">
        {section('Collected by treasury from now on, with new pay details', report.toNewFlow, 'success')}
        {section('Collected directly by the subsidiary', report.toDirect, 'info')}
        {section('Left as they are', report.skipped, 'neutral')}
        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      </div>
    </Modal>
  );
};
