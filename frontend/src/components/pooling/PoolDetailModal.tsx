import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Layers, Loader2, Calculator, UserPlus, Trash2, Calendar, Percent, Pencil, PauseCircle, XCircle, Play, Send, RotateCcw } from 'lucide-react';
import { Button, Badge, Input, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCurrency } from '../../utils';
import { NotionalPool, PoolPosting, PoolRun, poolingApi } from '../../services/api';
import toast from 'react-hot-toast';
import { VirtualizedAccountList, VirtualizedAccountRow } from '../va/VirtualizedAccountList';

// ============================================================================
// POOL DETAIL MODAL - VIEW AND MANAGE POOL
// ============================================================================
export interface PoolDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  pool: NotionalPool;
  onRemoveMember: (poolId: string, memberId: string) => Promise<void>;
  onAddMember: () => void;
  onEditPool: () => void;
  onSuspendPool: (poolId: string) => Promise<unknown>;
  onClosePool: (poolId: string) => Promise<unknown>;
}

export const PoolDetailModal: React.FC<PoolDetailModalProps> = ({
  isOpen,
  onClose,
  pool,
  onRemoveMember,
  onAddMember,
  onEditPool,
  onSuspendPool,
  onClosePool,
}) => {
  const [removingMemberId, setRemovingMemberId] = useState<string | null>(null);
  const [changingStatus, setChangingStatus] = useState(false);
  const [removeTargetId, setRemoveTargetId] = useState('');
  // Members who left keep their row (dated history); the pool is its current members.
  const members = useMemo(() => (pool.members || []).filter(m => m.status === 'ACTIVE'), [pool.members]);

  // PoolMember -> VirtualizedAccountRow: field names differ (accountNumber/
  // entityName vs. vaNumber/vaName), and members don't carry their own
  // currency, so the pool's currency is used for every row.
  const memberRows: VirtualizedAccountRow[] = useMemo(
    () => members.map(m => ({
      id: m.id,
      vaNumber: m.accountNumber,
      vaName: m.entityName,
      currencyCode: pool.poolCurrency,
      balance: m.currentBalance,
    })),
    [members, pool.poolCurrency]
  );

  const header = members.find(m => m.accountId === pool.headerAccountId);
  const rates = pool.rates;
  const open = pool.status !== 'CLOSED';

  const changeStatus = async (action: (poolId: string) => Promise<unknown>, question: string, done: string) => {
    if (!confirm(question)) return;
    setChangingStatus(true);
    try {
      await action(pool.id);
      toast.success(done);
    } catch (err: any) {
      toast.error(err.message || 'Failed to change the pool');
    } finally {
      setChangingStatus(false);
    }
  };

  const handleRemoveMember = async (memberId: string) => {
    if (!confirm('Remove this member from the pool? It leaves today; its history is kept.')) return;

    setRemovingMemberId(memberId);
    try {
      await onRemoveMember(pool.id, memberId);
      toast.success('Member removed from pool');
      setRemoveTargetId('');
    } catch (err: any) {
      toast.error(err.message || 'Failed to remove member');
    } finally {
      setRemovingMemberId(null);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={pool.poolName} size="lg">
      {/* Pool Header Info */}
      <div className="bg-primary-50 dark:bg-primary-500/10 rounded-lg p-4 mb-6">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="primary" icon={Layers} size="lg" solid />
            <div>
              <p className="text-caption text-neutral-500 dark:text-neutral-400 font-mono">{pool.poolReference}</p>
              <Badge variant={pool.status === 'ACTIVE' ? 'success' : 'warning'}>
                {pool.status}
              </Badge>
            </div>
          </div>
          {open && (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={onEditPool} leftIcon={<Pencil className="w-4 h-4" />}>
                Edit
              </Button>
              {pool.status === 'ACTIVE' && (
                <Button size="sm" variant="outline" disabled={changingStatus}
                        onClick={() => changeStatus(onSuspendPool, 'Suspend this pool? Its members stay; it can still be closed.', 'Pool suspended')}
                        leftIcon={<PauseCircle className="w-4 h-4" />}>
                  Suspend
                </Button>
              )}
              <Button size="sm" variant="ghost" disabled={changingStatus}
                      className="text-error-600 dark:text-error-300 hover:bg-error-50 dark:hover:bg-error-500/10"
                      onClick={() => changeStatus(onClosePool, 'Close this pool? Every member leaves today (history is kept); a closed pool cannot be reopened.', 'Pool closed')}
                      leftIcon={changingStatus ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}>
                Close Pool
              </Button>
            </div>
          )}
        </div>

        <div className="grid grid-cols-4 gap-4">
          <div>
            <p className="caption">Pool Balance</p>
            <p className="section-title">
              {formatCurrency(pool.totalBalance || 0, pool.poolCurrency)}
            </p>
          </div>
          <div>
            <p className="caption">Credit / Debit Rate</p>
            <p className="section-title">
              {rates ? `${rates.effectiveCreditRate ?? 0}% / ${rates.effectiveDebitRate ?? 0}%` : `${pool.interestRate}%`}
            </p>
            {rates && (
              <p className="caption">{rates.dayCountConvention}{rates.systemDefault ? ' · default rates' : ''}</p>
            )}
          </div>
          <div>
            <p className="caption">YTD Savings</p>
            <p className="text-body-lg font-semibold text-success-600 dark:text-success-300">
              {formatCurrency(pool.interestSavingsYtd || 0, pool.poolCurrency)}
            </p>
          </div>
          <div>
            <p className="caption">Members</p>
            <p className="section-title">{members.length}</p>
          </div>
        </div>
      </div>

      {/* Members Section */}
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h4 className="font-medium text-primary-900 dark:text-neutral-50">Pool Members</h4>
          <p className="caption">
            Header: <span className="font-mono">{header?.accountNumber || '—'}</span>
            {pool.poolFloor != null && <> · Debit floor {formatCurrency(pool.poolFloor, pool.poolCurrency)}</>}
          </p>
        </div>
        {open && (
          <Button size="sm" variant="outline" onClick={onAddMember} leftIcon={<UserPlus className="w-4 h-4" />}>
            Add Member
          </Button>
        )}
      </div>

      <VirtualizedAccountList items={memberRows} selectable={false} height={320} />

      {/* Per-member removal lives outside the (read-only) virtualized list —
          a trash icon per row doesn't scale to thousands of members, so this
          is a deliberate select-then-remove control instead. */}
      {open && members.length > 0 && (
        <div className="flex items-center gap-2 mt-3">
          <select
            value={removeTargetId}
            onChange={(e) => setRemoveTargetId(e.target.value)}
            className="flex-1 h-9 rounded-lg border border-edge bg-surface-card text-body-sm px-2 text-neutral-700 dark:text-neutral-200"
          >
            <option value="">Select a member to remove…</option>
            {members.map((member) => (
              // The header can't leave until another is chosen (Edit).
              <option key={member.id} value={member.id} disabled={member.accountId === pool.headerAccountId}>
                {member.entityName} — {member.accountNumber}{member.accountId === pool.headerAccountId ? ' (header)' : ''}
              </option>
            ))}
          </select>
          <Button
            variant="ghost"
            size="sm"
            className="text-error-600 dark:text-error-300 hover:bg-error-50 dark:hover:bg-error-500/10"
            onClick={() => removeTargetId && handleRemoveMember(removeTargetId)}
            disabled={!removeTargetId || removingMemberId === removeTargetId}
          >
            {removingMemberId && removingMemberId === removeTargetId ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Trash2 className="w-4 h-4" />
            )}
          </Button>
        </div>
      )}

      <DailyRuns pool={pool} />

      {/* Pool Info Footer */}
      <div className="mt-6 pt-4 border-t grid grid-cols-3 gap-4 text-body-sm">
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Calendar className="w-4 h-4" /> Effective From
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{pool.effectiveFrom || '-'}</p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Percent className="w-4 h-4" /> Calculation Method
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">
            {pool.interestCalculationMethod?.replace('_', ' ')}
          </p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Calculator className="w-4 h-4" /> Last Calculated
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{pool.lastCalculationDate || 'Never'}</p>
        </div>
      </div>

      <div className="flex justify-end mt-6">
        <Button variant="outline" onClick={onClose}>Close</Button>
      </div>
    </Modal>
  );
};

/** Local YYYY-MM-DD of yesterday: the latest date a run can take (today's balances are not final). */
const yesterday = () => {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return d.toLocaleDateString('en-CA');
};

/** Local YYYY-MM of last month: the latest month that can be posted (closing the pool posts the current one). */
const lastMonth = () => {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return d.toLocaleDateString('en-CA').slice(0, 7);
};

/** The pool's stored daily runs (last 31 days), a catch-up run for a date range, corrections and month postings. */
const DailyRuns: React.FC<{ pool: NotionalPool }> = ({ pool }) => {
  const [runs, setRuns] = useState<PoolRun[]>([]);
  const [postings, setPostings] = useState<PoolPosting[]>([]);
  const [from, setFrom] = useState(yesterday);
  const [to, setTo] = useState(yesterday);
  const [month, setMonth] = useState(lastMonth);
  const [running, setRunning] = useState(false);

  const load = useCallback(async () => {
    try {
      const [r, p] = await Promise.all([poolingApi.getRuns(pool.id), poolingApi.getPostings(pool.id)]);
      setRuns(r.data ?? []);
      setPostings(p.data ?? []);
    } catch (err: any) {
      toast.error(err.message || 'Failed to load daily runs');
    }
  }, [pool.id]);

  /** Runs an action with the busy flag, a toast and a reload. */
  const act = async (action: () => Promise<string>, failed: string) => {
    setRunning(true);
    try {
      toast.success(await action());
      await load();
    } catch (err: any) {
      toast.error(err.message || failed);
    } finally {
      setRunning(false);
    }
  };

  const postMonth = () => act(async () => {
    const done = (await poolingApi.postMonth(pool.id, month)).data ?? [];
    return done.length ? `${month} posted` : `Nothing to post for ${month}`;
  }, 'Posting failed');

  const correct = (date: string) => {
    if (!confirm(`Correct ${date}? Its run is superseded by a new one from today's data; the difference is posted now.`)) return;
    act(async () => {
      await poolingApi.correctRun(pool.id, date);
      return `${date} corrected`;
    }, 'Correction failed');
  };

  useEffect(() => { load(); }, [load]);

  const runDates = () => act(async () => {
    const done = (await poolingApi.runDates(pool.id, from, to)).data ?? [];
    return `${done.length} run${done.length === 1 ? '' : 's'} for ${from}${to !== from ? ` to ${to}` : ''}`;
  }, 'Run failed');

  const money = (v: number) => formatCurrency(v ?? 0, pool.poolCurrency);

  return (
    <div className="mt-6">
      <div className="flex flex-wrap items-end justify-between gap-3 mb-3">
        <div>
          <h4 className="font-medium text-primary-900 dark:text-neutral-50">Daily runs</h4>
          <p className="caption">End-of-day balances; pooled interest on the net. Benefit = pooled − standalone. Posted at month end.</p>
        </div>
        <div className="flex items-end gap-2">
          <Input id="pool-run-from" label="From" type="date" value={from} max={yesterday()} onChange={(e) => setFrom(e.target.value)} />
          <Input id="pool-run-to" label="To" type="date" value={to} max={yesterday()} onChange={(e) => setTo(e.target.value)} />
          <Button size="sm" variant="outline" onClick={runDates} disabled={running || !from || !to}
                  leftIcon={running ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}>
            Run dates
          </Button>
        </div>
      </div>
      {runs.length === 0 ? (
        <p className="caption">No runs in the last 31 days.</p>
      ) : (
        <div className="max-h-64 overflow-y-auto border border-edge rounded-lg">
          <table className="w-full text-body-sm">
            <thead className="text-neutral-500 dark:text-neutral-400 text-left sticky top-0 bg-surface-card">
              <tr>
                <th className="px-3 py-2 font-medium">Value date</th>
                <th className="px-3 py-2 font-medium text-right">Net</th>
                <th className="px-3 py-2 font-medium text-right">Pooled interest</th>
                <th className="px-3 py-2 font-medium text-right">Benefit</th>
                <th className="px-3 py-2 font-medium">Flags</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.id} className="border-t border-edge">
                  <td className="px-3 py-2 font-mono whitespace-nowrap">{r.valueDate}</td>
                  <td className="px-3 py-2 text-right tabular-nums whitespace-nowrap">{money(r.poolNet)}</td>
                  <td className="px-3 py-2 text-right tabular-nums whitespace-nowrap"
                      title={`${r.interestRate}% ${r.dayCount} (credit ${r.creditRate}% / debit ${r.debitRate}%)`}>
                    {money(r.pooledInterest)}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums whitespace-nowrap text-success-700 dark:text-success-300">{money(r.benefit)}</td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-1">
                      {r.floorBreach && <Badge variant="error" size="sm">Floor breach</Badge>}
                      {r.limitBreaches > 0 && <Badge variant="error" size="sm">{r.limitBreaches} over limit</Badge>}
                      {r.snapshotsMissing > 0 && <Badge variant="warning" size="sm">{r.snapshotsMissing} earlier balance{r.snapshotsMissing === 1 ? '' : 's'}</Badge>}
                      {r.status === 'POSTED' && <Badge variant="success" size="sm">Posted</Badge>}
                      {r.status === 'SUPERSEDED' && <Badge variant="neutral" size="sm">Superseded</Badge>}
                    </div>
                  </td>
                  <td className="px-3 py-2 text-right">
                    {r.status === 'POSTED' && (
                      <Button size="sm" variant="ghost" disabled={running} onClick={() => correct(r.valueDate)}
                              leftIcon={<RotateCcw className="w-4 h-4" />}>
                        Correct
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex flex-wrap items-end justify-between gap-3 mt-6 mb-3">
        <div>
          <h4 className="font-medium text-primary-900 dark:text-neutral-50">Postings</h4>
          <p className="caption">One interest movement per member a month, on its statement; corrections post the difference.</p>
        </div>
        <div className="flex items-end gap-2">
          <Input id="pool-post-month" label="Month" type="month" value={month} max={lastMonth()} onChange={(e) => setMonth(e.target.value)} />
          <Button size="sm" variant="outline" onClick={postMonth} disabled={running || !month}
                  leftIcon={running ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}>
            Post month
          </Button>
        </div>
      </div>
      {postings.length === 0 ? (
        <p className="caption">Nothing posted yet.</p>
      ) : (
        <div className="max-h-48 overflow-y-auto border border-edge rounded-lg">
          <table className="w-full text-body-sm">
            <thead className="text-neutral-500 dark:text-neutral-400 text-left sticky top-0 bg-surface-card">
              <tr>
                <th className="px-3 py-2 font-medium">Month</th>
                <th className="px-3 py-2 font-medium text-right">Total</th>
                <th className="px-3 py-2 font-medium">Posted at</th>
              </tr>
            </thead>
            <tbody>
              {postings.map(p => (
                <tr key={p.correlationId} className="border-t border-edge"
                    title={p.members.map(m => `${m.accountNumber}: ${money(m.amount)} (${m.reference})`).join('\n')}>
                  <td className="px-3 py-2 font-mono whitespace-nowrap">
                    {p.month}{p.kind === 'CORRECTION' && <Badge variant="info" size="sm" className="ml-2">Correction {p.valueDate}</Badge>}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums whitespace-nowrap">{money(p.total)}</td>
                  <td className="px-3 py-2 whitespace-nowrap">{p.postedAt?.replace('T', ' ').slice(0, 16)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
