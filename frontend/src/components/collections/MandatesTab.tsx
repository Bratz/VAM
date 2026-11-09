import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FileSignature, Plus, Upload, Download, PauseCircle, PlayCircle, XCircle, Pencil, CheckCircle, Ban, FileCode } from 'lucide-react';
import { Badge, Button, Checkbox, DataTable, Input, RowActions, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { partiesApi, virtualAccountsApi } from '../../services/api';
import { collectionsApi, Mandate, MandateBody, MandatePeriod, MandateType } from '../../services/collectionsApi';
import { errorText, downloadCsv } from '../../pages/fx/fxFormat';
import { formatCurrency, formatDate } from '../../utils';

interface Option { value: string; label: string }

const PERIOD_LABEL: Record<MandatePeriod, string> = { WEEK: 'a week', MONTH: 'a month', QUARTER: 'a quarter', YEAR: 'a year' };

const statusBadge = (m: Mandate) => {
  if (m.status === 'PENDING') return <Badge variant="warning" size="sm">Awaiting bank</Badge>;
  if (m.status === 'ACTIVE' && m.expired) return <Badge variant="neutral" size="sm">Expired</Badge>;
  const variant = m.status === 'ACTIVE' ? 'success' : m.status === 'SUSPENDED' ? 'warning' : 'neutral';
  return <Badge variant={variant} size="sm">{m.status.charAt(0) + m.status.slice(1).toLowerCase()}</Badge>;
};

const emptyForm = {
  customerPartyId: '', creditorVaId: '', mandateReference: '', schemeCode: 'UAE_DDS', debtorIban: '', debtorBic: '',
  currency: '', mandateType: 'RECURRING' as MandateType, validFrom: new Date().toISOString().slice(0, 10), validTo: '',
  maxPerCollection: '', frequencyCount: '', frequencyPeriod: '' as MandatePeriod | '',
};

/** Customer direct-debit mandates for one corporate: list, register one, upload a file, suspend / resume / cancel. */
export const MandatesTab: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [mandates, setMandates] = useState<Mandate[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [customers, setCustomers] = useState<Option[]>([]);
  const [accounts, setAccounts] = useState<Option[]>([]);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [electronic, setElectronic] = useState(false);
  const [amending, setAmending] = useState<Mandate | null>(null);
  const [amendment, setAmendment] = useState({ debtorIban: '', debtorBic: '', maxPerCollection: '', validTo: '' });
  const [refusing, setRefusing] = useState<Mandate | null>(null);
  const [refusal, setRefusal] = useState('MS02');
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    setError(null);
    try {
      setMandates(await collectionsApi.mandates(corporateId));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { load(); }, [load]);

  // The register form's customer and account lists belong to one corporate; drop them when it changes.
  useEffect(() => { setCustomers([]); setAccounts([]); }, [corporateId]);

  const openRegister = async () => {
    setForm(emptyForm);
    setFormError(null);
    setElectronic(false);
    setRegisterOpen(true);
    if (customers.length && accounts.length) return;
    try {
      const [parties, vas] = await Promise.all([
        partiesApi.getAll({ corporateId, pageSize: 500 } as any),
        virtualAccountsApi.getAll(0, 500, corporateId),
      ]);
      setCustomers((parties?.parties ?? []).map((p: any) => ({ value: p.id, label: p.partyCode ? `${p.legalName} · ${p.partyCode}` : p.legalName })));
      setAccounts(((vas?.data ?? []) as any[])
        .filter(v => v.status === 'ACTIVE' || v.status === undefined)
        .map(v => ({ value: v.id, label: `${v.vaNumber} · ${v.currencyCode}${v.vaName ? ` · ${v.vaName}` : ''}` })));
    } catch (err) {
      setFormError(errorText(err));
    }
  };

  const set = (k: keyof typeof emptyForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }));

  const register = async () => {
    setSaving(true);
    setFormError(null);
    const opt = (v: string) => (v.trim() ? v.trim() : undefined);
    const body: MandateBody = {
      corporateId, customerPartyId: form.customerPartyId, creditorVaId: form.creditorVaId,
      mandateReference: form.mandateReference.trim(), schemeCode: form.schemeCode.trim(),
      debtorIban: opt(form.debtorIban), debtorBic: opt(form.debtorBic), currency: opt(form.currency),
      mandateType: form.mandateType, validFrom: opt(form.validFrom), validTo: opt(form.validTo),
      maxPerCollection: form.maxPerCollection ? Number(form.maxPerCollection) : undefined,
      frequencyCount: form.frequencyCount ? Number(form.frequencyCount) : undefined,
      frequencyPeriod: form.frequencyPeriod || undefined,
      source: 'PORTAL',
    };
    try {
      if (electronic) {
        await collectionsApi.requestEMandate({ ...body, source: 'EMANDATE' });
        setNotice(`${body.mandateReference} was sent to the customer's bank. It can collect once the bank accepts it.`);
      } else {
        await collectionsApi.register(body);
      }
      setRegisterOpen(false);
      await load();
    } catch (err) {
      setFormError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const upload = async () => {
    const file = fileInput.current?.files?.[0];
    if (!file) { setUploadError('Choose a CSV file first.'); return; }
    setSaving(true);
    setUploadError(null);
    try {
      await collectionsApi.upload(corporateId, file);
      setUploadOpen(false);
      await load();
    } catch (err) {
      setUploadError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const act = async (m: Mandate, action: 'suspend' | 'resume' | 'cancel') => {
    try {
      await collectionsApi.changeStatus(m.id, action);
      await load();
    } catch (err) {
      setError(errorText(err));
    }
  };

  const startAmend = (m: Mandate) => {
    setAmendment({ debtorIban: '', debtorBic: '', maxPerCollection: '', validTo: '' });
    setFormError(null);
    setAmending(m);
  };

  const amend = async () => {
    if (!amending) return;
    setSaving(true);
    setFormError(null);
    const opt = (v: string) => (v.trim() ? v.trim() : undefined);
    try {
      const m = await collectionsApi.amend(amending.id, {
        debtorIban: opt(amendment.debtorIban), debtorBic: opt(amendment.debtorBic), validTo: opt(amendment.validTo),
        maxPerCollection: amendment.maxPerCollection ? Number(amendment.maxPerCollection) : undefined,
      });
      setNotice(m.awaitingBank === 'AMENDMENT'
        ? `The change to ${m.mandateReference} was sent to the customer's bank and applies once it accepts.`
        : `${m.mandateReference} was changed.`);
      setAmending(null);
      await load();
    } catch (err) {
      setFormError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const answer = async (m: Mandate, accepted: boolean, code?: string) => {
    try {
      const a = await collectionsApi.bankAnswer(m.id, accepted, code);
      setNotice(`${a.mandateReference}: ${{ ACTIVE: 'accepted, it can collect now', CANCELLED: 'refused, the mandate is cancelled',
        AMENDED: 'the change applies now', AMENDMENT_REFUSED: 'the change was refused', UNKNOWN: 'not found', UNCHANGED: 'nothing was waiting' }[a.result]}.`);
      setRefusing(null);
      await load();
    } catch (err) {
      setError(errorText(err));
    }
  };

  const startRefuse = async (m: Mandate) => {
    setRefusing(m);
    if (!Object.keys(reasons).length) {
      try {
        setReasons(await collectionsApi.returnReasons());
      } catch (err) {
        setError(errorText(err));
      }
    }
  };

  const downloadMessage = async (m: Mandate) => {
    try {
      await collectionsApi.downloadMandateMessage(m.id, m.mandateReference);
    } catch (err) {
      setError(errorText(err));
    }
  };

  const template = () => downloadCsv('mandates-template.csv',
    ['mandateReference', 'customer', 'creditorAccount', 'schemeCode', 'validFrom', 'debtorIban', 'debtorBic', 'currency',
      'type', 'validTo', 'signedDate', 'maxPerCollection', 'frequency'],
    [['MND-0001', 'Customer legal name or party code', 'VA-AED-000000-0000', 'UAE_DDS', '2026-11-01', 'AE070331234567890123456',
      '', 'AED', 'RECURRING', '', '', '5000', '1/MONTH']]);

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <p className="text-body-sm text-neutral-500 dark:text-neutral-400">
          Authorities to collect from a customer's bank account by direct debit. Invoices from customers without a usable mandate are sent a Request to Pay.
        </p>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => { setUploadError(null); setUploadOpen(true); }} leftIcon={<Upload className="w-4 h-4" />}>Upload mandates</Button>
          <Button size="sm" onClick={openRegister} disabled={!corporateId} leftIcon={<Plus className="w-4 h-4" />}>Register mandate</Button>
        </div>
      </div>
      {notice && <p className="text-body-sm text-success-700 dark:text-success-300">{notice}</p>}
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      <DataTable
        data={mandates}
        loading={loading}
        keyExtractor={(m) => m.id}
        emptyIcon={<FileSignature className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
        emptyTitle={corporateId ? 'No mandates yet' : 'Choose a corporate'}
        emptyDescription={corporateId ? 'Register a mandate or upload a file of them.' : undefined}
        columns={[
          {
            key: 'mandateReference', header: 'Mandate',
            render: (_, m) => (
              <div>
                <p className="text-body-sm font-mono whitespace-nowrap text-primary-900 dark:text-neutral-50">{m.mandateReference}</p>
                <p className="text-caption text-neutral-500 dark:text-neutral-400">{m.schemeCode} · {m.mandateType === 'ONE_OFF' ? 'One-off' : 'Recurring'}</p>
              </div>
            ),
          },
          {
            key: 'customerName', header: 'Customer',
            render: (_, m) => (
              <div>
                <p className="body-strong">{m.customerName ?? m.debtorName ?? '—'}</p>
                <p className="text-caption font-mono text-neutral-500 dark:text-neutral-400">{m.debtorIban}{m.debtorBic ? ` · ${m.debtorBic}` : ''}</p>
              </div>
            ),
          },
          {
            key: 'creditorAccount', header: 'Paid into',
            render: (_, m) => <span className="text-body-sm font-mono whitespace-nowrap">{m.creditorAccount ?? "—"}</span>,
          },
          {
            key: 'maxPerCollection', header: 'Limits',
            render: (_, m) => (
              <div className="text-body-sm">
                <p>{m.maxPerCollection != null ? `Up to ${formatCurrency(m.maxPerCollection, m.currency)}` : `Any amount in ${m.currency}`}</p>
                {m.frequencyCount != null && m.frequencyPeriod && (
                  <p className="text-caption text-neutral-500 dark:text-neutral-400">{m.frequencyCount}× {PERIOD_LABEL[m.frequencyPeriod]}</p>
                )}
              </div>
            ),
          },
          {
            key: 'validFrom', header: 'Valid',
            render: (_, m) => (
              <span className="text-body-sm">{formatDate(m.validFrom)} – {m.validTo ? formatDate(m.validTo) : 'until cancelled'}</span>
            ),
          },
          {
            key: 'status', header: 'Status', align: 'center',
            render: (_, m) => (
              <div className="flex flex-col items-center gap-1">
                {statusBadge(m)}
                {m.awaitingBank === 'AMENDMENT' && <Badge variant="warning" size="sm">Change awaiting bank</Badge>}
                {m.source === 'EMANDATE' && <span className="text-caption text-neutral-500 dark:text-neutral-400">e-mandate</span>}
              </div>
            ),
          },
          {
            key: 'id', header: '', align: 'right',
            render: (_, m) => (
              <RowActions actions={[
                { label: 'Bank accepted', icon: <CheckCircle className="w-4 h-4" />, onClick: () => answer(m, true), hidden: !m.awaitingBank },
                { label: 'Bank refused…', icon: <Ban className="w-4 h-4" />, onClick: () => startRefuse(m), hidden: !m.awaitingBank },
                { label: 'Amend…', icon: <Pencil className="w-4 h-4" />, onClick: () => startAmend(m),
                  hidden: (m.status !== 'ACTIVE' && m.status !== 'SUSPENDED') || m.awaitingBank === 'AMENDMENT', divider: !!m.awaitingBank },
                { label: 'Download last message', icon: <FileCode className="w-4 h-4" />, onClick: () => downloadMessage(m), hidden: !m.hasMessage },
                { label: 'Suspend', icon: <PauseCircle className="w-4 h-4" />, onClick: () => act(m, 'suspend'), hidden: m.status !== 'ACTIVE' },
                { label: 'Resume', icon: <PlayCircle className="w-4 h-4" />, onClick: () => act(m, 'resume'), hidden: m.status !== 'SUSPENDED' },
                { label: 'Cancel mandate', icon: <XCircle className="w-4 h-4" />, onClick: () => act(m, 'cancel'), danger: true, divider: true,
                  hidden: m.status === 'CANCELLED' || m.status === 'USED' },
              ]} />
            ),
          },
        ]}
      />

      <Modal
        isOpen={registerOpen}
        onClose={() => setRegisterOpen(false)}
        title="Register mandate"
        size="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setRegisterOpen(false)}>Cancel</Button>
            <Button onClick={register} loading={saving}
              disabled={!form.customerPartyId || !form.creditorVaId || !form.mandateReference.trim() || !form.schemeCode.trim()}>
              Register
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Select id="mandate-customer" label="Customer" value={form.customerPartyId} onChange={set('customerPartyId')} options={customers} placeholder="Choose a customer" />
          <Select id="mandate-account" label="Paid into" value={form.creditorVaId} onChange={set('creditorVaId')} options={accounts} placeholder="Choose an account" />
          <Input id="mandate-ref" label="Mandate reference" value={form.mandateReference} onChange={set('mandateReference')} maxLength={35} />
          <Input id="mandate-scheme" label="Scheme" value={form.schemeCode} onChange={set('schemeCode')} hint="e.g. UAE_DDS, SEPA_CORE" maxLength={20} />
          <Input id="mandate-iban" label="Customer IBAN" value={form.debtorIban} onChange={set('debtorIban')} hint="Blank: the customer's primary bank account" />
          <Input id="mandate-bic" label="Customer bank BIC" value={form.debtorBic} onChange={set('debtorBic')} maxLength={11} />
          <Input id="mandate-currency" label="Currency" value={form.currency} onChange={set('currency')} maxLength={3} hint="Blank: the customer account's, else the receiving account's" />
          <Select id="mandate-type" label="Type" value={form.mandateType} onChange={set('mandateType')}
            options={[{ value: 'RECURRING', label: 'Recurring' }, { value: 'ONE_OFF', label: 'One-off' }]} />
          <Input id="mandate-from" label="Valid from" type="date" value={form.validFrom} onChange={set('validFrom')} />
          <Input id="mandate-to" label="Valid to" type="date" value={form.validTo} onChange={set('validTo')} hint="Blank: until cancelled" />
          <Input id="mandate-max" label="Most per collection" type="number" min="0" step="0.01" value={form.maxPerCollection} onChange={set('maxPerCollection')} hint="Blank: no limit" />
          <div className="grid grid-cols-2 gap-2">
            <Input id="mandate-freq-count" label="At most" type="number" min="1" value={form.frequencyCount} onChange={set('frequencyCount')} />
            <Select id="mandate-freq-period" label="Collections" value={form.frequencyPeriod} onChange={set('frequencyPeriod')}
              options={[{ value: '', label: 'No cap' }, ...(['WEEK', 'MONTH', 'QUARTER', 'YEAR'] as MandatePeriod[]).map(p => ({ value: p, label: PERIOD_LABEL[p] }))]} />
          </div>
        </div>
        <div className="mt-4">
          <Checkbox checked={electronic} onChange={setElectronic}
            label="Send to the customer's bank for approval (e-mandate)"
            description="Sent as a pain.009. The mandate waits until the bank accepts it; a paper mandate you hold is usable at once." />
        </div>
        {formError && <p className="mt-3 text-body-sm text-error-700 dark:text-error-300">{formError}</p>}
      </Modal>

      <Modal
        isOpen={!!amending}
        onClose={() => setAmending(null)}
        title={amending ? `Amend ${amending.mandateReference}` : ''}
        subtitle={amending?.source === 'EMANDATE' ? "Sent to the customer's bank as a pain.010; it applies once the bank accepts it." : 'Applies at once. Leave a field blank to keep it.'}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAmending(null)}>Cancel</Button>
            <Button onClick={amend} loading={saving}>{amending?.source === 'EMANDATE' ? 'Send amendment' : 'Save'}</Button>
          </>
        }
      >
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Input id="amend-iban" label="New customer IBAN" value={amendment.debtorIban} placeholder={amending?.debtorIban}
            onChange={(e) => setAmendment(a => ({ ...a, debtorIban: e.target.value }))} />
          <Input id="amend-bic" label="New customer bank BIC" value={amendment.debtorBic} placeholder={amending?.debtorBic} maxLength={11}
            onChange={(e) => setAmendment(a => ({ ...a, debtorBic: e.target.value }))} />
          <Input id="amend-max" label="Most per collection" type="number" min="0" step="0.01" value={amendment.maxPerCollection}
            placeholder={amending?.maxPerCollection != null ? String(amending.maxPerCollection) : 'No limit'}
            onChange={(e) => setAmendment(a => ({ ...a, maxPerCollection: e.target.value }))} />
          <Input id="amend-to" label="Valid to" type="date" value={amendment.validTo}
            onChange={(e) => setAmendment(a => ({ ...a, validTo: e.target.value }))} />
        </div>
        {formError && <p className="mt-3 text-body-sm text-error-700 dark:text-error-300">{formError}</p>}
      </Modal>

      <Modal
        isOpen={!!refusing}
        onClose={() => setRefusing(null)}
        title={refusing ? `The bank refused ${refusing.mandateReference}` : ''}
        subtitle={refusing?.awaitingBank === 'AMENDMENT' ? 'The change is dropped; the mandate stays as it was.' : 'The mandate request is cancelled.'}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRefusing(null)}>Cancel</Button>
            <Button variant="danger" onClick={() => refusing && answer(refusing, false, refusal)}>Record refusal</Button>
          </>
        }
      >
        <Select id="refusal-reason" label="Reason from the payer's bank" value={refusal} onChange={(e) => setRefusal(e.target.value)}
          options={Object.entries(reasons).map(([code, text]) => ({ value: code, label: `${code} · ${text}` }))} />
      </Modal>

      <Modal
        isOpen={uploadOpen}
        onClose={() => setUploadOpen(false)}
        title="Upload mandates"
        footer={
          <>
            <Button variant="ghost" onClick={() => setUploadOpen(false)}>Cancel</Button>
            <Button onClick={upload} loading={saving} disabled={!corporateId} leftIcon={<Upload className="w-4 h-4" />}>Upload</Button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label htmlFor="mandate-file" className="block text-body-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">CSV file</label>
            <input id="mandate-file" ref={fileInput} type="file" accept=".csv,text/csv" className="block text-body-sm text-primary-900 dark:text-neutral-50" />
          </div>
          <Button size="sm" variant="secondary" onClick={template} leftIcon={<Download className="w-4 h-4" />}>Download template</Button>
          <p className="text-caption text-neutral-500 dark:text-neutral-400">
            Required: mandateReference, customer (legal name or party code), creditorAccount (the account number paid into), schemeCode,
            validFrom (YYYY-MM-DD). Optional: debtorIban and debtorBic (blank uses the customer's primary bank account), currency, type
            (RECURRING or ONE_OFF), validTo, signedDate, maxPerCollection, frequency (e.g. 1/MONTH). The whole file is rejected if any row is wrong.
          </p>
          {uploadError && <p className="text-body-sm text-error-700 dark:text-error-300">{uploadError}</p>}
        </div>
      </Modal>
    </div>
  );
};

export default MandatesTab;
