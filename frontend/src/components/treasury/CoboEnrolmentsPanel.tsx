import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, Plus, Undo2, XCircle } from 'lucide-react';
import { Badge, Button, DataTable, Select, TextArea } from '../ui';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { Modal } from '../ui/enhanced';
import { coboEnrolmentApi, legalEntityApi, type CoboCollectionAccount, type CoboCutoverItem, type CoboCutoverReport, type CoboEnrolment, type HeldFxCredit, type LegalEntity } from '../../services/api';
import { formatCurrency, formatDate, formatDateTime } from '../../utils';
import { FirmQuotePanel } from '../payments/FirmQuotePanel';

/**
 * Collections on behalf (COBO) of the selected corporate: which subsidiary, per currency, the treasury centre
 * collects for and into which account (spec docs/specs/cobo-in-house-bank.md R1-R4). Invoices of an enrolled
 * subsidiary in that currency are collected by treasury from creation; ending an enrolment affects new invoices only.
 */
export const CoboEnrolmentsPanel: React.FC<{
  corporateId: string;
  /** The corporate's IHB current accounts: an enrolment settles on one of its subsidiary's. */
  currentAccounts: IhbAccount[];
}> = ({ corporateId, currentAccounts }) => {
  const [enrolments, setEnrolments] = useState<CoboEnrolment[]>([]);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // Preview of the cut-over of old-flow COBO invoices (spec D7); re-run after every change to the enrolments.
  const [cutover, setCutover] = useState<CoboCutoverReport | null>(null);
  const [cutoverOpen, setCutoverOpen] = useState(false);
  // Inward credits above the live-price limit, held until treasury negotiates the rate (ihb-fx D8).
  const [held, setHeld] = useState<HeldFxCredit[]>([]);
  const [negotiating, setNegotiating] = useState<HeldFxCredit | null>(null);
  const [returning, setReturning] = useState<HeldFxCredit | null>(null);

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
      setHeld(await coboEnrolmentApi.awaitingFx(corporateId).catch(() => []));
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
      {held.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <span className="body-strong">Awaiting FX</span>
            <Badge variant="warning" size="sm">{held.length}</Badge>
          </div>
          <p className="caption">Received but not posted: above the live-price limit, so the rate is negotiated. Accepting a firm price posts the credit at it, pays the invoice and credits the subsidiary.</p>
          <DataTable
            data={held}
            keyExtractor={(h) => h.id}
            columns={[
              { key: 'debtorName', header: 'Payer', render: (_, h) => h.debtorName ?? '-' },
              { key: 'amount', header: 'Amount', align: 'right', minWidth: 140, render: (_, h) => formatCurrency(h.amount, h.currency) },
              { key: 'receivableNumber', header: 'Invoice', render: (_, h) => (
                <div className="flex flex-col"><span className="font-mono text-body-sm">{h.receivableNumber ?? '-'}</span><span className="caption font-mono">{h.viban ?? ''}{h.subsidiaryCode ? ' · ' + h.subsidiaryCode : ''}</span></div>
              ) },
              { key: 'createdAt', header: 'Received', render: (_, h) => (h.createdAt ? formatDateTime(h.createdAt) : '-') },
              { key: 'actions', header: '', align: 'right', minWidth: 210, render: (_, h) => (
                <div className="flex justify-end gap-2">
                  <Button size="xs" variant="outline" leftIcon={<ArrowRightLeft className="w-4 h-4" />} onClick={() => setNegotiating(h)}>Negotiate</Button>
                  <Button size="xs" variant="ghost" leftIcon={<Undo2 className="w-4 h-4" />} onClick={() => setReturning(h)}>Return</Button>
                </div>
              ) },
            ]}
          />
        </div>
      )}
      <DataTable
        data={enrolments}
        loading={loading}
        keyExtractor={(e) => e.id}
        emptyTitle="No subsidiary is collected on behalf"
        columns={[
          { key: 'subsidiaryName', header: 'Subsidiary', render: (_, e) => (
            <div className="flex flex-col"><span className="body-strong">{e.subsidiaryName ?? '-'}</span><span className="caption">{e.subsidiaryCode}</span></div>
          ) },
          { key: 'currency', header: 'Currency', minWidth: 90, render: (_, e) => (
            <div className="flex flex-col"><span>{e.currency}</span><span className="caption font-mono">{settledOn(e.currency, e.ihbAccountCurrency, e.ihbAccountNumber)}</span></div>
          ) },
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
      {negotiating && (
        <Modal
          isOpen
          onClose={() => setNegotiating(null)}
          title={`Negotiate ${negotiating.currency}/${negotiating.settleCurrency} for ${negotiating.receivableNumber ?? 'this credit'}`}
          subtitle={`${formatCurrency(negotiating.amount, negotiating.currency)} from ${negotiating.debtorName ?? 'the payer'}, received on ${negotiating.vaNumber}. Accepting posts it on this rate.`}
          size="md"
        >
          <FirmQuotePanel
            corporateId={corporateId}
            entityId={negotiating.collectorEntityId}
            pair={`${negotiating.currency}/${negotiating.settleCurrency}`}
            side="SELL"
            amount={negotiating.amount}
            acceptLabel="Accept and post"
            onAccept={async (quoteId) => {
              await coboEnrolmentApi.postHeld(negotiating.id, quoteId);
              setNegotiating(null);
              load();
            }}
          />
        </Modal>
      )}
      {returning && (
        <ReturnHeldModal credit={returning} onClose={() => setReturning(null)} onReturned={() => { setReturning(null); load(); }} />
      )}
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

/** Treasury will not convert a held credit: it goes back to the payer, with the reason (ihb-fx D8; no message in v1). */
const ReturnHeldModal: React.FC<{ credit: HeldFxCredit; onClose: () => void; onReturned: () => void }> = ({ credit, onClose, onReturned }) => {
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await coboEnrolmentApi.returnHeld(credit.id, reason.trim());
      onReturned();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not return the credit');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={`Return ${formatCurrency(credit.amount, credit.currency)} to ${credit.debtorName ?? 'the payer'}`}
      subtitle={`Not converted or posted; ${credit.receivableNumber ?? 'the invoice'} stays open. The funds are sent back by the bank.`}
      size="md"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" onClick={submit} loading={saving} disabled={!reason.trim()}>Return to payer</Button>
        </div>
      }
    >
      <div className="space-y-2">
        <TextArea label="Reason" value={reason} onChange={e => setReason(e.target.value)} rows={3}
          placeholder="Why treasury will not convert this credit" />
        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      </div>
    </Modal>
  );
};

/** An IHB current account an enrolment can settle on. */
type IhbAccount = { accountId: string; accountNumber: string; participantEntityId: string; currencyCode: string };

/** "→ settled on AED · VA-…" when the enrolment settles in another currency (ihb-fx D2), else the account number. */
const settledOn = (currency: string, ihbCurrency?: string | null, ihbNumber?: string | null) =>
  ihbCurrency && ihbCurrency !== currency ? `→ settled on ${ihbCurrency}${ihbNumber ? ' · ' + ihbNumber : ''}` : ihbNumber ?? '';

const AddEnrolmentModal: React.FC<{
  corporateId: string;
  entities: LegalEntity[];
  currentAccounts: IhbAccount[];
  onClose: () => void;
  onSaved: () => void;
}> = ({ corporateId, entities, currentAccounts, onClose, onSaved }) => {
  const collectors = entities.filter(e => e.isTreasuryCenter);
  const [subsidiaryId, setSubsidiaryId] = useState('');
  const [currency, setCurrency] = useState('');
  const [collectorId, setCollectorId] = useState(collectors.length === 1 ? collectors[0].id : '');
  const [accounts, setAccounts] = useState<CoboCollectionAccount[]>([]);
  const [accountId, setAccountId] = useState('');
  const [ihbAccountId, setIhbAccountId] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Any currency; it settles on one of the subsidiary's IHB current accounts, by default the one in that currency (ihb-fx D2).
  const ihbAccounts = useMemo(
    () => currentAccounts.filter(a => a.participantEntityId === subsidiaryId), [currentAccounts, subsidiaryId]);
  useEffect(() => {
    setIhbAccountId((ihbAccounts.find(a => a.currencyCode === currency) ?? (ihbAccounts.length === 1 ? ihbAccounts[0] : undefined))?.accountId ?? '');
  }, [ihbAccounts, currency]);
  const settleCurrency = ihbAccounts.find(a => a.accountId === ihbAccountId)?.currencyCode ?? '';

  useEffect(() => {
    setAccounts([]);
    setAccountId('');
    if (!collectorId || !settleCurrency) return;
    let live = true;
    coboEnrolmentApi.collectionAccounts(collectorId, settleCurrency)
      .then(list => { if (live) { setAccounts(list); setAccountId(list.length === 1 ? list[0].id : ''); } })
      .catch(() => { if (live) setAccounts([]); });
    return () => { live = false; };
  }, [collectorId, settleCurrency]);

  const subsidiary = entities.find(e => e.id === subsidiaryId);
  const collector = entities.find(e => e.id === collectorId);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await coboEnrolmentApi.create({ corporateId, subsidiaryEntityId: subsidiaryId, currency, collectorEntityId: collectorId, collectionVaId: accountId, ihbAccountId });
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
          <Button onClick={save} loading={saving} disabled={!subsidiaryId || !currency || !ihbAccountId || !collectorId || !accountId}>Enrol</Button>
        </div>
      }
    >
      <div className="space-y-4">
        <Select label="Subsidiary" value={subsidiaryId} onChange={e => setSubsidiaryId(e.target.value)}
          placeholder="Choose the subsidiary"
          options={entities.filter(e => !e.isTreasuryCenter).map(e => ({ value: e.id, label: `${e.entityName} (${e.entityCode})` }))} />
        <div>
          <label className="block body-strong mb-2">Invoice currency</label>
          <CurrencyPicker value={currency} onChange={setCurrency} allowEmpty emptyLabel="Choose the currency" />
        </div>
        <Select label="Settled on (IHB current account)" value={ihbAccountId} onChange={e => setIhbAccountId(e.target.value)}
          placeholder={subsidiaryId ? 'Choose the account the subsidiary is credited on' : 'Choose the subsidiary first'}
          disabled={!subsidiaryId || ihbAccounts.length === 0}
          error={subsidiary && ihbAccounts.length === 0
            ? `${subsidiary.entityCode} has no IHB current account; open one under Current Accounts first` : undefined}
          options={ihbAccounts.map(a => ({ value: a.accountId, label: `${a.accountNumber} · ${a.currencyCode}` }))} />
        <Select label="Collected by (treasury centre)" value={collectorId} onChange={e => setCollectorId(e.target.value)}
          placeholder="Choose the treasury centre"
          error={collectors.length === 0 ? 'No entity of this corporate is flagged as the treasury centre (Legal Entities)' : undefined}
          options={collectors.map(e => ({ value: e.id, label: `${e.entityName} (${e.entityCode})` }))} />
        <Select label="Collection account" value={accountId} onChange={e => setAccountId(e.target.value)}
          placeholder={collectorId && settleCurrency ? 'Choose the account customers pay into' : 'Choose the settle account and treasury centre first'}
          disabled={!collectorId || !settleCurrency || accounts.length === 0}
          error={collector && settleCurrency && accounts.length === 0
            ? `${collector.entityCode} has no active ${settleCurrency} collection account to collect into` : undefined}
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
