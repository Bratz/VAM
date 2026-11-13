import React, { useState, useEffect, useCallback } from 'react';
import toast from 'react-hot-toast';
import { AlertTriangle, ArrowLeftRight, Building2, CheckCircle, Clock, GitMerge, Landmark, Loader2, Receipt, RefreshCw, Scale, XCircle, Zap } from 'lucide-react';
import { Card, Button, Badge, Input, StatusIconBadge, StatTile, Checkbox } from '../components/ui';
import { TileAmount } from '../components/TileAmount';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Modal } from '../components/ui/enhanced';
import { usePageHeaderActions } from '../context/usePageHeader';
import { formatCurrency } from '../utils';
import { useNavigation } from '../hooks/useNavigation';
import { TreasuryOnly } from '../components/permissions';
import {
  corporatesApi, intercompanyApiEnhanced,
  type Corporate, type IcGroupPosition, type IcCurrencyPosition, type IcPairPosition, type IcLineGroup, type IcLine,
  type IcParty, type IcNettingEligibility,
} from '../services/api';

// The group's who-owes-whom (spec docs/specs/ihb-intercompany-pages.md, R3-R7): per currency and pair of entities,
// in-house bank positions, open intercompany invoices and recharges awaiting netting as separate lines; a zero-sum
// control per currency; one total in the reporting currency with its rates. Settling happens in Netting Cycles only.
// Recharges awaiting approval are shown apart (not owed yet, in no total) and approved or rejected here.

const formatDate = (d: string | null | undefined) => (d ? new Date(d).toLocaleDateString() : '');
const humanize = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '');
const who = (p: IcParty | null) => p?.code || p?.name || 'Unknown entity';

const ErrorMessage: React.FC<{ message: string; onRetry: () => void }> = ({ message, onRetry }) => (
  <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
    <div className="flex items-center gap-3 p-4">
      <StatusIconBadge tone="error" icon={XCircle} />
      <div className="flex-1">
        <p className="font-medium text-error-800 dark:text-error-300">Failed to load data</p>
        <p className="text-body-sm text-error-600 dark:text-error-300">{message}</p>
      </div>
      <Button variant="outline" size="sm" onClick={onRetry}>Retry</Button>
    </div>
  </Card>
);

/** "MNC-TREASURY owes MNC-UK AED 1,100.01" */
const Owes: React.FC<{ debtor: IcParty | null; creditor: IcParty | null; amount: number; currency: string }> = ({ debtor, creditor, amount, currency }) => (
  <span>
    <span className="font-medium text-error-600 dark:text-error-300">{who(debtor)}</span>
    {' owes '}
    <span className="font-medium text-success-600 dark:text-success-300">{who(creditor)}</span>
    {' '}
    <span className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(amount, currency)}</span>
  </span>
);

const LINE_LABEL: Record<IcLine['kind'], string> = {
  IHB_POSITION: 'In-house bank current account',
  RECEIVABLE: 'Invoice (receivable)',
  PAYABLE: 'Invoice (payable)',
  RECHARGE: 'Recharge',
  RECHARGE_PENDING: 'Recharge',
};

/** One kind of line within a pair: its lines and what they net to. */
const LineSection: React.FC<{
  title: string; icon: React.ComponentType<{ className?: string }>; group: IcLineGroup; currency: string;
  actions?: (line: IcLine) => React.ReactNode;
}> = ({ title, icon: Icon, group, currency, actions }) => (
  <div className="p-3 bg-surface-page rounded-lg">
    <div className="flex items-center justify-between mb-2">
      <span className="flex items-center gap-2 label"><Icon className="w-4 h-4" />{title}</span>
      {group.lines.length > 0 && (
        <span className="caption">
          {group.net.amount === 0 ? `Nets to ${formatCurrency(0, currency)}` : <>Net: <Owes debtor={group.net.debtor} creditor={group.net.creditor} amount={group.net.amount} currency={currency} /></>}
        </span>
      )}
    </div>
    {group.lines.length === 0 ? (
      <p className="caption">None</p>
    ) : (
      <div className="divide-y divide-edge-subtle">
        {group.lines.map((l, i) => (
          <div key={l.sourceId ?? `${l.kind}-${i}`} className="py-1.5 flex items-center justify-between gap-3 body-sm">
            <Owes debtor={l.debtor} creditor={l.creditor} amount={l.amount} currency={l.currency} />
            <span className="caption text-right">
              {l.reference ? `${l.reference} · ` : ''}{LINE_LABEL[l.kind]}
              {l.status ? ` · ${humanize(l.status)}` : ''}
              {l.dueDate ? ` · due ${formatDate(l.dueDate)}` : ''}
            </span>
            {actions?.(l)}
          </div>
        ))}
      </div>
    )}
  </div>
);

const PairCard: React.FC<{
  pair: IcPairPosition; currency: string; onNetting: () => void;
  onApprove: (line: IcLine) => void; onReject: (line: IcLine) => void; busy: string | null;
}> = ({ pair, currency, onNetting, onApprove, onReject, busy }) => {
  const nettable = pair.openInvoices.lines.length + pair.recharges.lines.length > 0;
  return (
    <Card hover>
      <div className="p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="primary" icon={Building2} size="sm" />
            <div>
              <p className="font-medium text-primary-900 dark:text-neutral-50">{who(pair.entityA)}</p>
              <p className="caption">{pair.entityA.name}</p>
            </div>
            <ArrowLeftRight className="w-5 h-5 text-neutral-300 dark:text-neutral-400" />
            <StatusIconBadge tone="info" icon={Building2} size="sm" />
            <div>
              <p className="font-medium text-primary-900 dark:text-neutral-50">{who(pair.entityB)}</p>
              <p className="caption">{pair.entityB.name}</p>
            </div>
          </div>
          {nettable && (
            <TreasuryOnly>
              <Button size="sm" variant="outline" leftIcon={<GitMerge className="w-4 h-4" />} onClick={onNetting}>
                Add to netting
              </Button>
            </TreasuryOnly>
          )}
        </div>
        <LineSection title="In-house bank" icon={Landmark} group={pair.ihbPositions} currency={currency} />
        <LineSection title="Open intercompany invoices" icon={Receipt} group={pair.openInvoices} currency={currency} />
        <LineSection title="Recharges awaiting netting" icon={Zap} group={pair.recharges} currency={currency} />
        {pair.rechargesAwaitingApproval.lines.length > 0 && (
          // The backend approves or rejects a recharge only while it is pending, which every line here is.
          <LineSection title="Recharges awaiting approval (not owed until approved)" icon={Clock}
            group={pair.rechargesAwaitingApproval} currency={currency}
            actions={l => l.sourceId && (
              <TreasuryOnly>
                <span className="flex gap-1 shrink-0">
                  <Button size="sm" variant="outline" disabled={busy === l.sourceId} leftIcon={<CheckCircle className="w-4 h-4" />} onClick={() => onApprove(l)}>Approve</Button>
                  <Button size="sm" variant="ghost" disabled={busy === l.sourceId} leftIcon={<XCircle className="w-4 h-4" />} onClick={() => onReject(l)}>Reject</Button>
                </span>
              </TreasuryOnly>
            )}
          />
        )}
      </div>
    </Card>
  );
};

/** The in-house bank nets to zero across the group, or the difference is a break. */
const ControlBadge: React.FC<{ position: IcCurrencyPosition }> = ({ position: { control, currency } }) =>
  control.balanced ? (
    <Badge variant="success">In-house bank nets to {formatCurrency(0, currency)}</Badge>
  ) : (
    <span className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-error-50 text-error-700 body-sm dark:bg-error-500/10 dark:text-error-300">
      <AlertTriangle className="w-4 h-4" />
      Break {formatCurrency(control.difference, currency)}: owed to entities {formatCurrency(control.receivables, currency)},
      owed by entities {formatCurrency(control.payables, currency)}
    </span>
  );

/** "converted at 1 GBP = 4.65 AED on 12/11/2026" for each currency that needed a rate. */
const conversionLabel = (total: IcGroupPosition['reportingTotal'], currencies: string[]) => {
  const parts = total.conversions.map(c =>
    `${c.currency} converted at 1 ${c.currency} = ${c.rate} ${total.currency}${c.rateDate ? ` on ${formatDate(c.rateDate)}` : ' (rate date unknown)'}`);
  if (parts.length === 0) parts.push(currencies.length > 0 ? `All in ${total.currency}, no conversion` : 'Nothing outstanding');
  if (total.unconvertedCurrencies.length > 0) parts.push(`not included (no rate): ${total.unconvertedCurrencies.join(', ')}`);
  return parts.join(' · ');
};

// ============================================================================
// NETTING HAND-OFF
// ============================================================================

const NettingModal: React.FC<{ corporateId: string; pair: IcPairPosition | null; onClose: () => void }> = ({ corporateId, pair, onClose }) => {
  const { navigate } = useNavigation();
  const [eligibility, setEligibility] = useState<IcNettingEligibility | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [cycleId, setCycleId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    if (!pair) return;
    let current = true;
    setEligibility(null);
    setError(null);
    intercompanyApiEnhanced.getNettingEligibility(corporateId, pair.entityA.id, pair.entityB.id)
      .then(r => {
        if (!current) return;
        setEligibility(r.data);
        setSelected(new Set(r.data.items.map(i => i.id)));
        setCycleId(r.data.openCycles[0]?.id ?? '');
      })
      .catch((e: any) => current && setError(e.response?.data?.message || e.message || 'Failed to load'));
    return () => { current = false; };
  }, [corporateId, pair]);

  const add = async () => {
    if (!eligibility) return;
    const chosen = eligibility.items.filter(i => selected.has(i.id));
    const ids = (kind: string) => chosen.filter(i => i.kind === kind).map(i => i.id);
    setAdding(true);
    try {
      const r = await intercompanyApiEnhanced.addToNetting(corporateId, cycleId,
        { rechargeIds: ids('RECHARGE'), payableIds: ids('PAYABLE'), receivableIds: ids('RECEIVABLE') });
      toast.success(`${r.data.totalAdded} added to ${r.data.cycleReference}`);
      onClose();
      navigate('netting-enhanced', { cycleId });
    } catch (e: any) {
      toast.error(e.response?.data?.message || e.message || 'Failed to add to the netting cycle');
    } finally {
      setAdding(false);
    }
  };

  const toggle = (id: string, on: boolean) => setSelected(prev => {
    const next = new Set(prev);
    if (on) next.add(id); else next.delete(id);
    return next;
  });

  return (
    <Modal
      isOpen={!!pair}
      onClose={onClose}
      title="Add to a netting cycle"
      subtitle={pair ? `${who(pair.entityA)} and ${who(pair.entityB)}` : undefined}
      size="lg"
      footer={(
        <div className="flex justify-between w-full">
          <Button variant="ghost" onClick={() => navigate('netting-enhanced')}>Go to Netting Cycles</Button>
          <div className="flex gap-2">
            <Button variant="ghost" onClick={onClose}>Cancel</Button>
            <Button onClick={add} disabled={adding || !cycleId || selected.size === 0} leftIcon={adding ? <Loader2 className="w-4 h-4 animate-spin" /> : <GitMerge className="w-4 h-4" />}>
              Add to cycle
            </Button>
          </div>
        </div>
      )}
    >
      {error && <p className="body-sm text-error-600 dark:text-error-300">{error}</p>}
      {!error && !eligibility && (
        <div className="flex items-center gap-2 body-sm"><Loader2 className="w-4 h-4 animate-spin" /> Checking what can be netted…</div>
      )}
      {eligibility && (
        <div className="space-y-4">
          {eligibility.reason && <p className="body-sm text-warning-700 dark:text-warning-300">{eligibility.reason}</p>}
          {!eligibility.reason && eligibility.items.length === 0 && (
            <p className="body-sm">Nothing between these entities can go into a netting cycle now: netting takes an approved
              payable, a receivable still owed (open, overdue or partly paid), and an approved recharge not already in a cycle.</p>
          )}
          {eligibility.items.length > 0 && (
            <div className="space-y-2">
              {eligibility.items.map(i => (
                <Checkbox
                  key={i.id}
                  variant="card"
                  checked={selected.has(i.id)}
                  onChange={on => toggle(i.id, on)}
                  label={<span>{i.debtorCode} owes {i.creditorCode} <span className="font-semibold">{formatCurrency(i.amount, i.currency)}</span></span>}
                  description={`${i.reference} · ${i.kind === 'RECHARGE' ? 'Recharge' : 'Invoice'}`}
                />
              ))}
            </div>
          )}
          {eligibility.items.length > 0 && (
            eligibility.openCycles.length === 0 ? (
              <p className="body-sm">No netting cycle of this corporate is open. Create one in Netting Cycles, then come back.</p>
            ) : (
              <div>
                <label className="field-label block mb-1" htmlFor="ic-netting-cycle">Netting cycle</label>
                <select id="ic-netting-cycle" className="w-full border border-edge rounded-lg px-3 py-2 bg-surface-card" value={cycleId} onChange={e => setCycleId(e.target.value)}>
                  {eligibility.openCycles.map(c => (
                    <option key={c.id} value={c.id}>{c.cycleReference} · {c.cycleName} ({humanize(c.status)}, base {c.baseCurrency})</option>
                  ))}
                </select>
              </div>
            )
          )}
        </div>
      )}
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const IntercompanyDashboardPage: React.FC = () => {
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [position, setPosition] = useState<IcGroupPosition | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nettingPair, setNettingPair] = useState<IcPairPosition | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<IcLine | null>(null);
  const [reason, setReason] = useState('');

  useEffect(() => {
    corporatesApi.getAll(0, 100)
      .then(r => {
        const corps = r.data || [];
        setCorporates(corps);
        if (corps.length > 0) setSelectedCorporateId(corps[0].id);
        else setLoading(false);
      })
      .catch(() => { setCorporates([]); setLoading(false); });
  }, []);

  const fetchData = useCallback(async () => {
    if (!selectedCorporateId) return;
    setLoading(true);
    setError(null);
    try {
      const r = await intercompanyApiEnhanced.getGroupPosition(selectedCorporateId);
      setPosition(r.data);
    } catch (e: any) {
      setPosition(null);
      setError(e.response?.data?.message || e.message || 'Failed to load intercompany positions');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const decide = async (line: IcLine, approve: boolean, why = '') => {
    if (!line.sourceId) return;
    setBusy(line.sourceId);
    try {
      if (approve) await intercompanyApiEnhanced.approveRecharge(selectedCorporateId, line.sourceId);
      else await intercompanyApiEnhanced.rejectRecharge(selectedCorporateId, line.sourceId, why);
      toast.success(`${line.reference} ${approve ? 'approved' : 'rejected'}`);
      setRejecting(null);
      setReason('');
      fetchData();
    } catch (e: any) {
      toast.error(e.response?.data?.message || e.message || `Failed to ${approve ? 'approve' : 'reject'} ${line.reference}`);
    } finally {
      setBusy(null);
    }
  };

  usePageHeaderActions(
    () => (
      <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>
        Refresh
      </Button>
    ),
    [fetchData]
  );

  const scope = (
    <ScopeSelector
      mode="corporate-only"
      requireSelection
      corporates={corporates}
      selectedCorporateId={selectedCorporateId}
      onCorporateChange={setSelectedCorporateId}
      loading={loading}
    />
  );

  if (error) return <Page>{scope}<ErrorMessage message={error} onRetry={fetchData} /></Page>;
  if (loading || !position) {
    return (
      <Page>
        {scope}
        <div className="flex items-center justify-center py-12">
          <StatusIconBadge tone="primary" icon={Loader2} size="lg" spin />
          <span className="ml-3 text-neutral-600 font-medium dark:text-neutral-300">Loading intercompany positions...</span>
        </div>
      </Page>
    );
  }

  const pairs = position.currencies.flatMap(c => c.pairs);
  const count = (pick: (p: IcPairPosition) => IcLineGroup) => pairs.reduce((n, p) => n + pick(p).lines.length, 0);
  const breaks = position.currencies.filter(c => !c.control.balanced);
  const awaitingApproval = count(p => p.rechargesAwaitingApproval);
  const total = position.reportingTotal;

  return (
    <Page>
      {scope}

      <HeroMetricCard
        primary={{
          label: `Intercompany outstanding in ${total.currency}`,
          value: <TileAmount value={total.amount} currency={total.currency} />,
          sub: conversionLabel(total, position.currencies.filter(c => c.outstanding > 0).map(c => c.currency)),
        }}
        secondary={{
          label: 'Group control',
          value: breaks.length === 0 ? 'Balanced' : `${breaks.length} break${breaks.length > 1 ? 's' : ''}`,
          sub: breaks.length === 0
            ? 'In-house bank positions net to zero in every currency'
            : breaks.map(b => formatCurrency(b.control.difference, b.currency)).join(', '),
        }}
        icon={<Scale className="w-6 h-6 text-accent-600 dark:text-accent-300" />}
      />

      <StatStrip>
        <StatTile layout="row" tone="primary" icon={<Landmark className="w-5 h-5" />} label="In-house bank positions" value={count(p => p.ihbPositions)} delay="0.18s" />
        <StatTile layout="row" tone="warning" icon={<Receipt className="w-5 h-5" />} label="Open invoices" value={count(p => p.openInvoices)} delay="0.21s" />
        <StatTile layout="row" tone="info" icon={<Zap className="w-5 h-5" />} label="Recharges awaiting netting" value={count(p => p.recharges)} sub={awaitingApproval > 0 ? `${awaitingApproval} more awaiting approval` : undefined} delay="0.24s" />
        <StatTile layout="row" tone="accent" icon={<ArrowLeftRight className="w-5 h-5" />} label="Entity pairs" value={pairs.length} delay="0.27s" />
      </StatStrip>

      {position.allSettled && (
        <Card className="animate-fade-in">
          <div className="px-4 py-12 text-center">
            <StatusIconBadge tone="success" icon={CheckCircle} size="xl" className="mx-auto mb-4" />
            <p className="text-neutral-500 font-medium dark:text-neutral-400">
              Nothing is owed between entities: no in-house bank position, open intercompany invoice or recharge awaiting netting
            </p>
          </div>
        </Card>
      )}

      {position.currencies.map((c, ci) => (
        <section key={c.currency} className="space-y-4 animate-fade-in" style={{ animationDelay: `${0.3 + ci * 0.05}s` }}>
          <div className="flex items-center justify-between gap-4 flex-wrap">
            <div className="flex items-center gap-3">
              <h3 className="section-title">{c.currency}</h3>
              <span className="body-sm">{formatCurrency(c.outstanding, c.currency)} outstanding between entities</span>
            </div>
            <ControlBadge position={c} />
          </div>
          {c.pairs.length === 0 ? (
            <Card><p className="p-4 body-sm">No positions between entities in {c.currency}.</p></Card>
          ) : (
            <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
              {c.pairs.map(p => (
                <PairCard key={`${p.entityA.id}-${p.entityB.id}`} pair={p} currency={c.currency} onNetting={() => setNettingPair(p)}
                  onApprove={l => decide(l, true)} onReject={l => { setReason(''); setRejecting(l); }} busy={busy} />
              ))}
            </div>
          )}
        </section>
      ))}

      <NettingModal corporateId={selectedCorporateId} pair={nettingPair} onClose={() => setNettingPair(null)} />

      <Modal
        isOpen={!!rejecting}
        onClose={() => setRejecting(null)}
        title={`Reject recharge ${rejecting?.reference ?? ''}`}
        subtitle={rejecting ? `${who(rejecting.debtor)} would owe ${who(rejecting.creditor)} ${formatCurrency(rejecting.amount, rejecting.currency)}` : undefined}
        size="md"
        footer={(
          <div className="flex justify-end gap-2 w-full">
            <Button variant="ghost" onClick={() => setRejecting(null)}>Cancel</Button>
            <Button variant="danger" disabled={!reason.trim() || busy === rejecting?.sourceId}
              onClick={() => rejecting && decide(rejecting, false, reason.trim())}>Reject</Button>
          </div>
        )}
      >
        <label className="field-label block mb-1" htmlFor="ic-reject-reason">Reason</label>
        <Input id="ic-reject-reason" placeholder="Why this recharge is rejected" value={reason} onChange={e => setReason(e.target.value)} />
      </Modal>
    </Page>
  );
};

export default IntercompanyDashboardPage;
