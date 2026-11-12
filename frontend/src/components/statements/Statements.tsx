// Bank statements, read-only (spec R2a, R11, R16 / D7): the intake log and an account's statement
// lines. Nothing here submits a statement — they arrive through the inbox or the system intake endpoint.

import React, { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, FileText } from 'lucide-react';
import { Badge, Card, CardHeader, DataTable } from '../ui';
import { formatCurrency, formatDate, formatDateTime } from '../../utils';
import { statementApi, BankStatement, BankStatementLine, StatementFileLog } from '../../services/statementApi';

/** A yyyy-MM-dd date in local time (a bare date string would parse as UTC midnight). */
const day = (d?: string) => (d ? formatDate(`${d}T00:00:00`) : '—');

const STATEMENT_STATUS: Record<BankStatement['status'], 'success' | 'neutral' | 'error'> = {
  APPLIED: 'success', DUPLICATE: 'neutral', REFUSED: 'error',
};

const LINE_STATUS: Record<BankStatementLine['status'], 'success' | 'warning' | 'neutral'> = {
  FINAL: 'success', PROVISIONAL: 'warning', SUPERSEDED: 'neutral',
};

const StatementFlags: React.FC<{ s: BankStatement }> = ({ s }) => (
  <>
    {s.kind === 'INTRADAY' && <Badge variant="info" size="xs">Intraday</Badge>}
    {s.gap && <Badge variant="warning" size="xs"><span title="Sequence number skips one or more statements">Gap</span></Badge>}
    {s.breakFlag && <Badge variant="error" size="xs"><span title="Opening balance differs from the previous closing balance">Break</span></Badge>}
    {s.older && <Badge variant="neutral" size="xs"><span title="Older than the latest statement: its day's snapshot only, balance left alone">Older</span></Badge>}
    {s.cbsFed && <Badge variant="neutral" size="xs"><span title="Account is fed by core banking: stored, balance left to core banking">Core banking fed</span></Badge>}
  </>
);

const accountOf = (s: BankStatement) => s.iban || s.accountNumber || 'Unknown account';

/** Intake log (R2a): every statement file the system took in, expandable to its statements. */
export const StatementIntakeLog: React.FC = () => {
  const [log, setLog] = useState<StatementFileLog[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    // A failed fetch hides the card rather than claiming no files arrived.
    statementApi.files().then(setLog).catch(() => setLog(null));
  }, []);

  if (!log) return null;
  return (
    <Card>
      <CardHeader
        title="Bank (automatic)"
        subtitle="Bank statements taken in from the bank's delivery folder or its system-to-system feed. Read-only: nobody uploads a statement."
      />
      {log.length === 0 ? (
        <p className="caption">No statement files received yet.</p>
      ) : (
        <div className="space-y-1 -mx-2">
          {log.map(({ file, statements }) => {
            const expanded = open === file.id;
            return (
              <div key={file.id}>
                <button
                  type="button"
                  onClick={() => setOpen(expanded ? null : file.id)}
                  className="w-full flex items-center justify-between gap-3 px-2 py-2 rounded-lg text-left hover:bg-neutral-50 dark:hover:bg-primary-950 transition-colors"
                >
                  <div className="flex items-center gap-2 min-w-0">
                    {expanded ? <ChevronDown className="w-4 h-4 shrink-0 text-neutral-400" /> : <ChevronRight className="w-4 h-4 shrink-0 text-neutral-400" />}
                    <FileText className="w-4 h-4 shrink-0 text-neutral-400" />
                    <div className="min-w-0">
                      <p className="text-body-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{file.fileName}</p>
                      <p className="caption">
                        {file.channel === 'INBOX' ? 'Inbox' : 'API'} · {formatDateTime(file.receivedAt)} · {file.appliedCount} applied · {file.duplicateCount} duplicate · {file.refusedCount} refused
                      </p>
                    </div>
                  </div>
                  <Badge variant={file.status === 'FAILED' ? 'error' : file.refusedCount > 0 ? 'warning' : 'success'} size="sm">
                    {file.status === 'FAILED' ? 'Not read' : file.refusedCount > 0 ? 'Some refused' : 'Processed'}
                  </Badge>
                </button>
                {expanded && (
                  <div className="ml-8 mr-2 mb-2 space-y-2">
                    {file.error && <p className="caption-error">{file.error}</p>}
                    {statements.map((s) => (
                      <div key={s.id} className="p-2 rounded-lg border border-edge-subtle">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-mono text-caption text-primary-900 dark:text-neutral-50 truncate">{accountOf(s)}</span>
                          <Badge variant={STATEMENT_STATUS[s.status]} size="xs">{s.status}</Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-1.5 mt-1">
                          <span className="caption">{s.statementId ?? 'No id'} · {day(s.statementDate)}</span>
                          <StatementFlags s={s} />
                        </div>
                        {s.reason && <p className="caption mt-1">{s.reason}</p>}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
};

/** Statement lines on an account (R11), grouped under their statement, newest first. Renders nothing without statements. */
export const AccountStatementLines: React.FC<{ physicalAccountId: string; currency: string }> = ({ physicalAccountId, currency }) => {
  const [data, setData] = useState<{ statements: BankStatement[]; lines: BankStatementLine[] } | null>(null);

  useEffect(() => {
    setData(null);
    statementApi.lines(physicalAccountId).then(setData).catch(() => setData(null));
  }, [physicalAccountId]);

  if (!data || data.statements.length === 0) return null;
  return (
    <div className="space-y-3">
      <p className="label">Bank statement lines</p>
      {data.statements.map((s) => {
        const lines = data.lines.filter((l) => l.bankStatementId === s.id);
        return (
          <div key={s.id} className="rounded-lg border border-edge-subtle">
            <div className="flex flex-wrap items-center gap-1.5 px-3 py-2">
              <span className="text-body-sm font-medium text-primary-900 dark:text-neutral-50">{day(s.statementDate)}</span>
              <span className="caption">{s.statementId ?? ''}{s.closingBooked != null ? ` · closing ${formatCurrency(s.closingBooked, s.currency ?? currency)}` : ''}</span>
              <StatementFlags s={s} />
            </div>
            {lines.length > 0 && (
              <DataTable
                hairline
                data={lines}
                keyExtractor={(l) => l.id}
                columns={[
                  { key: 'valueDate', header: 'Value date', minWidth: 100, render: (_v, l) => <span className="caption">{day(l.valueDate)}</span> },
                  { key: 'status', header: 'Status', minWidth: 100, render: (_v, l) => <Badge variant={LINE_STATUS[l.status]} size="xs">{l.status}</Badge> },
                  { key: 'amount', header: 'Amount', align: 'right', minWidth: 130, render: (_v, l) => (
                    <span className="amount text-primary-900 dark:text-neutral-50">
                      {formatCurrency(l.creditDebit === 'D' ? -l.amount : l.amount, s.currency ?? currency)}{l.reversal ? ' (reversal)' : ''}
                    </span>
                  ) },
                  { key: 'reference', header: 'Reference', minWidth: 140, dropOrder: 2, render: (_v, l) => (
                    <span className="font-mono text-caption">{l.endToEndId ?? l.bankReference ?? l.customerReference ?? '—'}</span>
                  ) },
                  { key: 'remittanceText', header: 'Details', minWidth: 160, dropOrder: 1, render: (_v, l) => (
                    <span className="caption block truncate max-w-[16rem]" title={l.remittanceText}>{l.remittanceText ?? '—'}</span>
                  ) },
                ]}
              />
            )}
          </div>
        );
      })}
    </div>
  );
};
