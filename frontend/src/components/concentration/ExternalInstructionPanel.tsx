// The request for transfer of a sweep from an account at another bank (spec docs/specs/external-bank-sweeps.md R19):
// the MT101 / pain.001 itself, its status, fees, and the statement line that confirmed it or why it failed.

import React, { useCallback, useEffect, useState } from 'react';
import { Ban, Download, FileText } from 'lucide-react';
import { Badge, Button, Card, Input } from '../ui';
import { formatCurrency, formatDate, formatDateTime } from '../../utils';
import { sweepingApi, type SweepInstruction, type SweepInstructionView } from '../../services/api';

const STATUS: Partial<Record<SweepInstruction['status'], { label: string; variant: 'success' | 'warning' | 'error' | 'info' }>> = {
  INSTRUCTED: { label: 'Instructed', variant: 'info' },
  SETTLED: { label: 'Confirmed', variant: 'success' },
  REJECTED: { label: 'Rejected', variant: 'error' },
  EXPIRED: { label: 'Expired', variant: 'error' },
};

/** A yyyy-MM-dd date in local time (a bare date string would parse as UTC midnight). */
const day = (d?: string) => (d ? formatDate(`${d}T00:00:00`) : '—');

const apiError = (e: unknown, fallback: string) =>
  (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;

const download = (i: SweepInstruction) => {
  const xml = i.instructionFormat === 'PAIN_001';
  const url = URL.createObjectURL(new Blob([i.messageText ?? ''], { type: xml ? 'application/xml' : 'text/plain' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `${i.externalReference}.${xml ? 'xml' : 'txt'}`;
  a.click();
  URL.revokeObjectURL(url);
};

const Row: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex justify-between gap-3 text-body-sm">
    <span className="text-neutral-500 dark:text-neutral-400">{label}</span>
    <span className="text-right text-primary-900 dark:text-neutral-50">{children}</span>
  </div>
);

/**
 * Renders nothing for an execution without an instruction, except a COMMITTED one: those predate external sweeps
 * (positions committed by the old end-of-day settlement) and say so.
 */
export const ExternalInstructionPanel: React.FC<{ executionId: string; executionStatus: string; onChanged?: () => void }> = ({
  executionId, executionStatus, onChanged,
}) => {
  const [view, setView] = useState<SweepInstructionView | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    sweepingApi.getInstruction(executionId).then((v) => setView(v ?? null))
      .catch((e) => { setView(null); setError(apiError(e, 'Could not load the instruction')); });
  }, [executionId]);

  useEffect(() => { setView(undefined); setError(null); setRejecting(null); load(); }, [load]);

  const reject = async (i: SweepInstruction, reason: string) => {
    setBusy(true);
    setError(null);
    try {
      await sweepingApi.markRejected(i.id, reason);
      setRejecting(null);
    } catch (e) {
      setError(apiError(e, 'The instruction was not marked rejected'));
    } finally {
      setBusy(false);
      load();   // a refusal may have confirmed it from a stored statement
      onChanged?.();
    }
  };

  if (view === undefined) return null;
  if (view === null) {
    if (error) return <p className="caption-error">{error}</p>;
    return executionStatus === 'COMMITTED'
      ? <p className="caption">No instruction: committed before external-bank sweeps, by the old end-of-day settlement.</p>
      : null;
  }

  const { instruction: i, confirmingLine: line, standardFee } = view;
  const ccy = i.currencyCode;
  const status = STATUS[i.status] ?? { label: i.status, variant: 'info' as const };
  const failed = i.status === 'REJECTED' || i.status === 'EXPIRED';

  return (
    <Card padding="sm" className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <p className="label flex items-center gap-1.5"><FileText className="w-4 h-4" />Instruction to the other bank</p>
        <Badge variant={status.variant} size="sm" dot>{status.label}</Badge>
      </div>

      <div className="space-y-1.5">
        <Row label="Format">{i.instructionFormat === 'PAIN_001' ? 'pain.001' : 'MT101'}</Row>
        <Row label="Reference"><span className="font-mono">{i.externalReference}</span></Row>
        <Row label="Amount">{formatCurrency(i.amount, ccy)}</Row>
        <Row label="Execution date">{day(i.executionDate)}</Row>
        {i.instructedAt && <Row label="Instructed">{formatDateTime(i.instructedAt)}</Row>}
        <Row label="Standard fee">{standardFee != null ? formatCurrency(standardFee, ccy) : i.standardFeeRef ?? 'none'} · charged to the target</Row>
        <Row label="Instruction fee">
          {i.instructionFeeRef ? formatCurrency(i.instructionFee ?? 0, ccy) : 'not charged'}
          {i.instructionFeeRef && failed && ' · refunded'}
        </Row>
      </div>

      {i.status === 'SETTLED' && (
        <div className="rounded-lg border border-success-200/60 dark:border-success-500/30 bg-success-50/50 dark:bg-success-500/10 px-3 py-2 text-body-sm">
          <p className="font-semibold text-success-800 dark:text-success-300">Confirmed by the other bank's statement</p>
          <p className="text-success-700 dark:text-success-300">
            {line ? `Debit of ${formatCurrency(line.amount, ccy)} valued ${day(line.valueDate)}` : 'Statement line'}
            {i.reconciliationRef && <> · bank reference <span className="font-mono">{i.reconciliationRef}</span></>}
            {i.settledAt && ` · ${formatDateTime(i.settledAt)}`}
          </p>
          {i.confirmedByLineId && <p className="caption font-mono">line {i.confirmedByLineId}</p>}
        </div>
      )}

      {failed && (
        <div className="rounded-lg border border-error-200/60 dark:border-error-500/30 bg-error-50/50 dark:bg-error-500/10 px-3 py-2 text-body-sm">
          <p className="font-semibold text-error-800 dark:text-error-300">{status.label}{i.rejectedAt && ` ${formatDateTime(i.rejectedAt)}`}</p>
          <p className="text-error-700 dark:text-error-300">{i.rejectionReason}</p>
          <p className="caption">The target's credit was reversed and the instruction fee refunded; the standard fee stands.</p>
        </div>
      )}

      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <p className="caption">Message</p>
          <Button size="xs" variant="ghost" leftIcon={<Download className="w-3 h-3" />} onClick={() => download(i)} disabled={!i.messageText}>
            Download
          </Button>
        </div>
        <pre className="font-mono text-caption whitespace-pre overflow-auto max-h-64 rounded-lg bg-surface-muted p-3 text-primary-900 dark:text-neutral-100">
          {i.messageText}
        </pre>
      </div>

      {error && <p className="caption-error">{error}</p>}

      {i.status === 'INSTRUCTED' && (rejecting === null ? (
        <div className="flex justify-end">
          <Button size="xs" variant="outline" leftIcon={<Ban className="w-3 h-3" />} onClick={() => setRejecting('')}>Mark rejected</Button>
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <Input inputSize="sm" placeholder="Reason from the other bank (required)" aria-label="Rejection reason" value={rejecting}
            maxLength={500} onChange={(e) => setRejecting(e.target.value)} />
          <Button size="xs" variant="ghost" onClick={() => setRejecting(null)}>Cancel</Button>
          <Button size="xs" variant="danger" loading={busy} disabled={!rejecting.trim()} onClick={() => reject(i, rejecting)}>
            Mark rejected
          </Button>
        </div>
      ))}
    </Card>
  );
};
