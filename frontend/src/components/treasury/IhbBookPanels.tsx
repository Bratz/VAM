import React, { useCallback, useEffect, useState } from 'react';
import { Download, FileText } from 'lucide-react';
import { Badge, Button, Card, DataTable, Input, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import {
  ihbBookApi,
  type IhbCurrencyPosition, type IhbInterestHistory, type IhbMovement, type IhbMovementDetail, type IhbMovementKind,
  type IhbStatement, type IhbTermsChange,
} from '../../services/api';
import { downloadCsv, errorText } from '../../pages/fx/fxFormat';
import { cn, formatCurrency, formatDate, formatDateTime } from '../../utils';

/**
 * The in-house bank's book on the In-House Bank page (spec ihb-intercompany-pages D7, D10; R13-R17): position per entity
 * and currency with treasury's funding beside it, movements with their transaction, statements, interest history at the
 * rates charged, and the limit/rate changes. Who owes whom between entities is on the Intercompany page, not here.
 */

export type IhbBookAccount = {
  accountId: string; accountNumber: string; currencyCode: string; participantEntityId: string;
  participantEntityCode?: string; participantEntityName?: string;
};
type BookEntity = { id: string; entityCode: string; entityName: string };

const KIND_LABEL: Record<IhbMovementKind, string> = {
  SWEEP: 'Sweep', POBO: 'Payment on behalf', COBO: 'Collection on behalf', INTEREST: 'Interest',
  SETTLEMENT: 'Settlement', FEE: 'Fee', OTHER: 'Other',
};

const tone = (n: number) => (n > 0 ? 'text-success-700 dark:text-success-300' : n < 0 ? 'text-error-700 dark:text-error-300' : '');

const Money: React.FC<{ value: number | null | undefined; currency: string; signed?: boolean; strong?: boolean }> = ({ value, currency, signed, strong }) =>
  value == null ? <span className="caption">-</span>
    : <span className={cn('tabular-nums whitespace-nowrap', signed && tone(value), strong && 'font-semibold')}>{formatCurrency(value, currency)}</span>;

const pct = (n: number | null | undefined) => (n == null ? '-' : `${Number(n).toFixed(2)}%`);
const today = () => new Date().toISOString().slice(0, 10);
const yearStart = () => `${new Date().getFullYear()}-01-01`;
const accountLabel = (a: IhbBookAccount) => `${a.accountNumber} · ${a.participantEntityName ?? a.participantEntityCode ?? ''} (${a.currencyCode})`;

const PeriodInputs: React.FC<{ from: string; to: string; onChange: (from: string, to: string) => void }> = ({ from, to, onChange }) => (
  <>
    <Input type="date" label="From" value={from} max={to} onChange={(e) => onChange(e.target.value, to)} inputSize="sm" />
    <Input type="date" label="To" value={to} min={from} onChange={(e) => onChange(from, e.target.value)} inputSize="sm" />
  </>
);

// ---------------------------------------------------------------------------------------------------- position (R13)

export const IhbPositionPanel: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [currencies, setCurrencies] = useState<IhbCurrencyPosition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    ihbBookApi.position(corporateId).then(setCurrencies).catch((e) => setError(errorText(e))).finally(() => setLoading(false));
  }, [corporateId]);

  if (error) return <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>;
  if (!loading && currencies.length === 0) return <p className="body-sm">No in-house bank current accounts and no treasury bank accounts yet.</p>;

  return (
    <div className="space-y-6">
      <p className="body-sm">
        Each entity&apos;s current account at the in-house bank, per currency: its balance (positive: the in-house bank owes it),
        credit limit, how much of it is used and what is left, and interest accrued but not yet posted. The credit limit applies
        in the entity&apos;s in-house bank currency only. Beside it, treasury&apos;s bank accounts that fund the in-house bank.
      </p>
      {currencies.map((c) => (
        <div key={c.currency} className="grid grid-cols-3 gap-4">
          <div className="col-span-2 space-y-2">
            <h3 className="section-title">{c.currency}</h3>
            <DataTable
              data={c.rows}
              loading={loading}
              keyExtractor={(r) => `${r.entityId}-${r.currency}`}
              emptyTitle={`No current accounts in ${c.currency}`}
              columns={[
                { key: 'entityCode', header: 'Entity', render: (_, r) => (
                  <div className="flex flex-col"><span className="body-strong">{r.entityName ?? r.entityCode}</span><span className="caption">{r.entityCode}{r.accounts > 1 ? ` · ${r.accounts} accounts` : ''}</span></div>
                ) },
                { key: 'balance', header: 'Balance', align: 'right', render: (_, r) => <Money value={r.balance} currency={r.currency} signed /> },
                { key: 'creditLimit', header: 'Credit limit', align: 'right', render: (_, r) => <Money value={r.creditLimit} currency={r.currency} /> },
                { key: 'used', header: 'Used', align: 'right', render: (_, r) => <Money value={r.used} currency={r.currency} /> },
                { key: 'headroom', header: 'Headroom', align: 'right', render: (_, r) => <Money value={r.headroom} currency={r.currency} /> },
                { key: 'accruedInterest', header: 'Accrued interest', align: 'right', render: (_, r) => <Money value={r.accruedInterest} currency={r.currency} signed /> },
              ]}
            />
            {c.rows.length > 0 && (
              <div className="flex justify-end gap-6 text-body-sm px-2">
                <span>Net owed to entities <Money value={c.totalBalance} currency={c.currency} signed strong /></span>
                <span>Accrued interest <Money value={c.totalAccruedInterest} currency={c.currency} signed strong /></span>
              </div>
            )}
          </div>
          <Card padding="sm" className="space-y-2 self-start">
            <p className="label">Funded by treasury&apos;s bank accounts</p>
            {c.funding.length === 0 && <p className="caption">No treasury bank account in {c.currency}.</p>}
            {c.funding.map((f) => (
              <div key={f.id} className="flex justify-between gap-3 text-body-sm">
                <div className="flex flex-col min-w-0"><span className="truncate">{f.accountName ?? f.accountNumber}</span><span className="caption font-mono">{f.bankName ? `${f.bankName} · ` : ''}{f.accountNumber}</span></div>
                <div className="flex flex-col items-end"><Money value={f.balance} currency={f.currency} /><span className="caption">available {formatCurrency(f.available, f.currency)}</span></div>
              </div>
            ))}
            {c.funding.length > 1 && (
              <div className="flex justify-between pt-2 border-t border-edge text-body-sm"><span>Total</span><Money value={c.totalFunding} currency={c.currency} strong /></div>
            )}
          </Card>
        </div>
      ))}
    </div>
  );
};

// ---------------------------------------------------------------------------------------- movements and statement (R14)

export const IhbMovementsPanel: React.FC<{ corporateId: string; entities: BookEntity[]; accounts: IhbBookAccount[] }> = ({ corporateId, entities, accounts }) => {
  const [filter, setFilter] = useState({ entityId: '', currency: '', kind: '', from: yearStart(), to: today() });
  const [movements, setMovements] = useState<IhbMovement[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<IhbMovementDetail | null>(null);
  const [statementAccount, setStatementAccount] = useState('');
  const [statement, setStatement] = useState<IhbStatement | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setMovements(await ihbBookApi.movements(corporateId, {
        entityId: filter.entityId || undefined, currency: filter.currency || undefined, kind: filter.kind || undefined,
        from: filter.from || undefined, to: filter.to || undefined,
      }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [corporateId, filter]);

  useEffect(() => { load(); }, [load]);

  const open = async (m: IhbMovement) => {
    try { setDetail(await ihbBookApi.movement(corporateId, m.id)); } catch (e) { setError(errorText(e)); }
  };
  const openStatement = async () => {
    try { setStatement(await ihbBookApi.statement(corporateId, statementAccount, { from: filter.from, to: filter.to })); } catch (e) { setError(errorText(e)); }
  };
  const currencies = Array.from(new Set(accounts.map(a => a.currencyCode))).sort();

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-5 gap-3 items-end">
        <Select label="Entity" selectSize="sm" value={filter.entityId} onChange={(e) => setFilter({ ...filter, entityId: e.target.value })}>
          <option value="">All entities</option>
          {entities.map(e => <option key={e.id} value={e.id}>{e.entityName} ({e.entityCode})</option>)}
        </Select>
        <Select label="Currency" selectSize="sm" value={filter.currency} onChange={(e) => setFilter({ ...filter, currency: e.target.value })}>
          <option value="">All currencies</option>
          {currencies.map(c => <option key={c} value={c}>{c}</option>)}
        </Select>
        <Select label="Type" selectSize="sm" value={filter.kind} onChange={(e) => setFilter({ ...filter, kind: e.target.value })}>
          <option value="">All types</option>
          {(Object.keys(KIND_LABEL) as IhbMovementKind[]).map(k => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
        </Select>
        <PeriodInputs from={filter.from} to={filter.to} onChange={(from, to) => setFilter({ ...filter, from, to })} />
      </div>
      <div className="flex items-end gap-3">
        <div className="w-96">
          <Select label="Statement of" selectSize="sm" value={statementAccount} onChange={(e) => setStatementAccount(e.target.value)}>
            <option value="">Choose a current account</option>
            {accounts.map(a => <option key={a.accountId} value={a.accountId}>{accountLabel(a)}</option>)}
          </Select>
        </div>
        <Button size="sm" variant="outline" leftIcon={<FileText className="w-4 h-4" />} disabled={!statementAccount} onClick={openStatement}>
          Statement for the period
        </Button>
      </div>
      {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      <DataTable
        data={movements}
        loading={loading}
        keyExtractor={(m) => m.id}
        onRowClick={open}
        emptyTitle="No movements in this period"
        columns={[
          { key: 'bookedAt', header: 'Booked', minWidth: 120, render: (_, m) => formatDate(m.bookedAt) },
          { key: 'valueDate', header: 'Value date', minWidth: 110, dropOrder: 2, render: (_, m) => (m.valueDate ? formatDate(m.valueDate) : '-') },
          { key: 'accountNumber', header: 'Account', render: (_, m) => (
            <div className="flex flex-col"><span className="font-mono text-body-sm">{m.accountNumber}</span><span className="caption">{m.entityCode ?? ''}</span></div>
          ) },
          { key: 'kind', header: 'Type', minWidth: 150, render: (_, m) => <Badge variant={m.kind === 'INTEREST' ? 'accent' : m.kind === 'OTHER' ? 'neutral' : 'info'} size="sm">{KIND_LABEL[m.kind]}</Badge> },
          { key: 'description', header: 'Description', dropOrder: 1, render: (_, m) => <span className="body-sm">{m.description ?? '-'}</span> },
          { key: 'amount', header: 'Amount', align: 'right', render: (_, m) => <Money value={m.amount} currency={m.currency} signed /> },
          { key: 'balanceAfter', header: 'Balance after', align: 'right', dropOrder: 3, render: (_, m) => <Money value={m.balanceAfter} currency={m.currency} /> },
        ]}
      />
      <MovementModal detail={detail} onClose={() => setDetail(null)} />
      <StatementModal corporateId={corporateId} statement={statement} onClose={() => setStatement(null)} onError={setError} />
    </div>
  );
};

const MovementModal: React.FC<{ detail: IhbMovementDetail | null; onClose: () => void }> = ({ detail, onClose }) => {
  const m = detail?.movement;
  return (
    <Modal isOpen={!!detail} onClose={onClose} title={m ? `${KIND_LABEL[m.kind]} · ${m.reference}` : ''} subtitle={m ? `${m.accountNumber} · ${m.entityCode ?? ''}` : undefined} size="lg">
      {detail && m && (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-4 text-body-sm">
            <div><p className="caption">Amount</p><Money value={m.amount} currency={m.currency} signed strong /></div>
            <div><p className="caption">Balance after</p><Money value={m.balanceAfter} currency={m.currency} /></div>
            <div><p className="caption">Status</p><span>{m.status ?? '-'}</span></div>
            <div><p className="caption">Booked</p><span>{formatDateTime(m.bookedAt)}</span></div>
            <div><p className="caption">Value date</p><span>{m.valueDate ? formatDate(m.valueDate) : '-'}</span></div>
            <div><p className="caption">Transaction</p><span className="font-mono">{detail.transactionReference ?? m.reference}</span></div>
            {detail.counterpartyName && <div><p className="caption">Counterparty</p><span>{detail.counterpartyName}</span></div>}
            {detail.counterpartyAccount && <div><p className="caption">Counterparty account</p><span className="font-mono">{detail.counterpartyAccount}</span></div>}
            {detail.paymentReference && <div><p className="caption">Payment reference</p><span className="font-mono">{detail.paymentReference}</span></div>}
          </div>
          {m.description && <p className="body-sm">{m.description}</p>}
          <div>
            <p className="label mb-2">The transaction&apos;s legs</p>
            <DataTable
              data={detail.legs}
              keyExtractor={(l) => `${l.account}-${l.amount}-${l.description}`}
              compact
              columns={[
                { key: 'account', header: 'Account', render: (_, l) => <div className="flex flex-col"><span className="text-body-sm">{l.account ?? '-'}</span><span className="caption">{l.entityCode ?? ''}</span></div> },
                { key: 'description', header: 'Description', render: (_, l) => <span className="body-sm">{l.description ?? '-'}</span> },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, l) => <Money value={l.amount} currency={l.currency} signed /> },
              ]}
            />
          </div>
        </div>
      )}
    </Modal>
  );
};

const StatementModal: React.FC<{ corporateId: string; statement: IhbStatement | null; onClose: () => void; onError: (e: string) => void }> = ({ corporateId, statement: s, onClose, onError }) => {
  const csv = () => s && downloadCsv(`statement-${s.accountNumber}-${s.from}-${s.to}.csv`,
    ['Booked', 'Value date', 'Reference', 'Type', 'Description', 'Amount', 'Currency', 'Balance after'],
    [
      ['', s.from, '', '', 'Opening balance', '', s.currency, s.openingBalance],
      ...s.lines.map(l => [l.bookedAt, l.valueDate ?? '', l.reference, KIND_LABEL[l.kind], l.description ?? '', l.amount, l.currency, l.balanceAfter ?? '']),
      ['', s.to, '', '', 'Closing balance', '', s.currency, s.closingBalance],
    ]);
  const camt = async () => {
    if (!s) return;
    try {
      const url = URL.createObjectURL(await ihbBookApi.statementCamt053(corporateId, s.accountId, s.from, s.to));
      const a = document.createElement('a');
      a.href = url;
      a.download = `statement-${s.accountNumber}-${s.from}-${s.to}.xml`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      onError(errorText(e));
    }
  };
  return (
    <Modal isOpen={!!s} onClose={onClose} title={s ? `Statement · ${s.accountNumber}` : ''}
      subtitle={s ? `${s.accountName ?? s.entityCode ?? ''} · ${formatDate(s.from)} to ${formatDate(s.to)}` : undefined} size="xl"
      footer={s && (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" leftIcon={<Download className="w-4 h-4" />} onClick={csv}>CSV</Button>
          <Button size="sm" variant="outline" leftIcon={<Download className="w-4 h-4" />} onClick={camt}>camt.053 XML</Button>
        </div>
      )}>
      {s && (
        <div className="space-y-4">
          <div className="grid grid-cols-4 gap-4 text-body-sm">
            <div><p className="caption">Opening balance</p><Money value={s.openingBalance} currency={s.currency} strong /></div>
            <div><p className="caption">Credits</p><Money value={s.totalCredits} currency={s.currency} /></div>
            <div><p className="caption">Debits</p><Money value={s.totalDebits} currency={s.currency} /></div>
            <div><p className="caption">Closing balance</p><Money value={s.closingBalance} currency={s.currency} strong /></div>
          </div>
          <DataTable
            data={s.lines}
            keyExtractor={(l) => l.id}
            compact
            emptyTitle="No movements in this period"
            columns={[
              { key: 'bookedAt', header: 'Booked', render: (_, l) => formatDate(l.bookedAt) },
              { key: 'valueDate', header: 'Value date', render: (_, l) => (l.valueDate ? formatDate(l.valueDate) : '-') },
              { key: 'description', header: 'Description', render: (_, l) => <div className="flex flex-col"><span className="body-sm">{l.description ?? KIND_LABEL[l.kind]}</span><span className="caption font-mono">{l.reference}</span></div> },
              { key: 'amount', header: 'Amount', align: 'right', render: (_, l) => <Money value={l.amount} currency={l.currency} signed /> },
              { key: 'balanceAfter', header: 'Balance', align: 'right', render: (_, l) => <Money value={l.balanceAfter} currency={l.currency} /> },
            ]}
          />
        </div>
      )}
    </Modal>
  );
};

// ------------------------------------------------------------------------------------------------ interest history (R15)

export const IhbInterestPanel: React.FC<{ corporateId: string; accounts: IhbBookAccount[] }> = ({ corporateId, accounts }) => {
  const [accountId, setAccountId] = useState('');
  const [period, setPeriod] = useState({ from: yearStart(), to: today() });
  const [history, setHistory] = useState<IhbInterestHistory | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { if (!accountId && accounts.length) setAccountId(accounts[0].accountId); }, [accounts, accountId]);
  useEffect(() => {
    if (!accountId) return;
    setError(null);
    ihbBookApi.interestHistory(corporateId, accountId, period).then(setHistory).catch((e) => { setHistory(null); setError(errorText(e)); });
  }, [corporateId, accountId, period]);

  const h = history;
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-3 items-end">
        <div className="col-span-2">
          <Select label="Current account" selectSize="sm" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {accounts.map(a => <option key={a.accountId} value={a.accountId}>{accountLabel(a)}</option>)}
          </Select>
        </div>
        <PeriodInputs from={period.from} to={period.to} onChange={(from, to) => setPeriod({ from, to })} />
      </div>
      {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      {accounts.length === 0 && <p className="body-sm">No in-house bank current accounts.</p>}
      {h && (
        <>
          <Card padding="sm">
            <div className="grid grid-cols-4 gap-4 text-body-sm">
              <div><p className="caption">Charged now on a credit balance</p><span className="body-strong">{pct(h.creditRateNow)}</span></div>
              <div><p className="caption">Charged now on a debit balance</p><span className="body-strong">{pct(h.debitRateNow)}</span></div>
              <div><p className="caption">Day count</p><span className="body-strong">{h.dayCountNow}</span></div>
              <div><p className="caption">Accrued, not yet posted</p>
                <span className="flex flex-col"><Money value={h.unpostedCredit} currency={h.currency} /> <span className="caption">earned</span><Money value={h.unpostedDebit} currency={h.currency} /> <span className="caption">owed</span></span>
              </div>
            </div>
            {h.defaultRatesNow && <p className="caption mt-2 text-warning-700 dark:text-warning-300">No rate is configured on this account for one side: the built-in default rate is charged there.</p>}
          </Card>
          <div className="space-y-2">
            <div className="flex justify-between items-baseline">
              <p className="label">Daily accruals</p>
              <span className="text-body-sm">Earned <Money value={h.accruedCredit} currency={h.currency} /> · Owed <Money value={h.accruedDebit} currency={h.currency} /></span>
            </div>
            <DataTable
              data={h.accruals}
              keyExtractor={(a) => `${a.date}-${a.side}-${a.amount}-${a.note ?? ''}`}
              compact
              emptyTitle="No accruals recorded in this period"
              emptyDescription="Each day's accrual is recorded from this release on; interest posted earlier is listed below."
              columns={[
                { key: 'date', header: 'Accrued on', render: (_, a) => formatDate(a.date) },
                { key: 'side', header: 'Side', render: (_, a) => (a.side === 'CREDIT' ? 'Earned' : 'Owed') },
                { key: 'balance', header: 'Balance', align: 'right', render: (_, a) => <Money value={a.balance} currency={h.currency} signed /> },
                { key: 'rate', header: 'Rate charged', align: 'right', render: (_, a) => <span>{pct(a.rate)}{a.defaultRate && <span className="caption"> (default)</span>}</span> },
                { key: 'dayCount', header: 'Day count', render: (_, a) => a.dayCount ?? '-' },
                { key: 'amount', header: 'Interest', align: 'right', render: (_, a) => <div className="flex flex-col items-end"><Money value={a.amount} currency={h.currency} />{a.note && <span className="caption">{a.note}</span>}</div> },
              ]}
            />
          </div>
          <div className="space-y-2">
            <div className="flex justify-between items-baseline">
              <p className="label">Interest posted to the account</p>
              <span className="text-body-sm">Net <Money value={h.posted} currency={h.currency} signed /></span>
            </div>
            <DataTable
              data={h.postings}
              keyExtractor={(p) => p.movementId}
              compact
              emptyTitle="No interest posted in this period"
              columns={[
                { key: 'postedAt', header: 'Posted', render: (_, p) => formatDate(p.postedAt) },
                { key: 'valueDate', header: 'Value date', render: (_, p) => (p.valueDate ? formatDate(p.valueDate) : '-') },
                { key: 'description', header: 'Description', render: (_, p) => <span className="body-sm">{p.description ?? '-'}</span> },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, p) => <Money value={p.amount} currency={h.currency} signed /> },
              ]}
            />
          </div>
        </>
      )}
    </div>
  );
};

// ------------------------------------------------------------------------------------------- limit and rate changes (R16)

const termValue = (v: number | string | null, currency: string | null) =>
  v == null ? 'not set' : currency ? formatCurrency(Number(v), currency) : pct(Number(v));

export const IhbChangesPanel: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [changes, setChanges] = useState<IhbTermsChange[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    ihbBookApi.termsHistory(corporateId).then(setChanges).catch((e) => setError(errorText(e))).finally(() => setLoading(false));
  }, [corporateId]);

  return (
    <div className="space-y-4">
      <p className="body-sm">
        Changes to an entity&apos;s credit limit or spreads and to the rates a current account is charged: who, from what to what,
        and when. A rate with &quot;not set&quot; is charged at the built-in default.
      </p>
      {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}
      <DataTable
        data={changes}
        loading={loading}
        keyExtractor={(c) => `${c.at}-${c.subject}-${c.field}`}
        emptyTitle="No credit limit or rate changes recorded"
        columns={[
          { key: 'at', header: 'When', render: (_, c) => formatDateTime(c.at) },
          { key: 'by', header: 'Who', render: (_, c) => c.by ?? '-' },
          { key: 'subject', header: 'Entity or account', render: (_, c) => <span className="font-mono text-body-sm">{c.subject ?? '-'}</span> },
          { key: 'field', header: 'What', render: (_, c) => c.field },
          { key: 'from', header: 'From', align: 'right', render: (_, c) => termValue(c.from, c.currency) },
          { key: 'to', header: 'To', align: 'right', render: (_, c) => <span className="font-semibold">{termValue(c.to, c.currency)}</span> },
        ]}
      />
    </div>
  );
};
