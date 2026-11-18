// Bank statements, read-only (spec R2a, R11, R16 / D7): the intake log and an account's statement
// lines. Nothing here submits a statement — they arrive through the inbox or the system intake endpoint.

import React, { useEffect, useMemo, useState } from 'react';
import { FileText } from 'lucide-react';
import { Badge, Card, CardHeader, DataTable, Drawer } from '../ui';
import { Tabs } from '../ui/enhanced';
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

type FileOutcome = 'processed' | 'refused' | 'failed';

const FILE_OUTCOME: Record<FileOutcome, { label: string; variant: 'success' | 'warning' | 'error' }> = {
  processed: { label: 'Processed', variant: 'success' },
  refused: { label: 'Some refused', variant: 'warning' },
  failed: { label: 'Not read', variant: 'error' },
};

const fileOutcome = ({ file }: StatementFileLog): FileOutcome =>
  file.status === 'FAILED' ? 'failed' : file.refusedCount > 0 ? 'refused' : 'processed';

const PAGE_SIZE = 10;
/** The log's backend cap (StatementIntakeController MAX_FILES); paged and filtered here. */
const FILES_FETCHED = 200;

/** Intake log (R2a): every statement file the system took in; a file opens to its statements. */
export const StatementIntakeLog: React.FC = () => {
  const [log, setLog] = useState<StatementFileLog[] | null>(null);
  const [filter, setFilter] = useState<FileOutcome | 'all'>('processed');
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<StatementFileLog | null>(null);

  useEffect(() => {
    // A failed fetch hides the card rather than claiming no files arrived.
    statementApi.files(FILES_FETCHED).then(setLog).catch(() => setLog(null));
  }, []);

  const counts = useMemo(() => {
    const c: Record<FileOutcome, number> = { processed: 0, refused: 0, failed: 0 };
    (log ?? []).forEach((f) => { c[fileOutcome(f)] += 1; });
    return c;
  }, [log]);
  const shown = useMemo(() => (log ?? []).filter((f) => filter === 'all' || fileOutcome(f) === filter), [log, filter]);

  if (!log) return null;
  const pick = (id: string) => { setFilter(id as FileOutcome | 'all'); setPage(1); };
  return (
    <Card>
      <CardHeader
        title="Bank statements (automatic)"
        subtitle="Taken in from the bank's delivery folder or its system-to-system feed. Read-only: nobody uploads a statement."
      />
      {log.length > 0 && (
        <div className="mb-4 overflow-x-auto">
          <Tabs
            variant="pills"
            size="sm"
            activeTab={filter}
            onChange={pick}
            tabs={[
              ...(Object.keys(FILE_OUTCOME) as FileOutcome[])
                .filter((o) => o === 'processed' || counts[o] > 0)
                .map((o) => ({ id: o, label: FILE_OUTCOME[o].label, badge: counts[o] })),
              { id: 'all', label: 'All', badge: log.length },
            ]}
          />
        </div>
      )}
      <DataTable
        hairline
        data={shown.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)}
        keyExtractor={({ file }) => file.id}
        onRowClick={setOpen}
        pagination
        pageSize={PAGE_SIZE}
        currentPage={page}
        totalCount={shown.length}
        onPageChange={setPage}
        emptyIcon={<FileText className="w-10 h-10" />}
        emptyTitle={log.length === 0 ? 'No statement files received yet' : `No files ${FILE_OUTCOME[filter as FileOutcome]?.label.toLowerCase() ?? ''}`.trim()}
        emptyDescription={log.length === 0 ? 'Files appear here as the banks deliver them.' : 'Choose another filter to see the rest.'}
        columns={[
          { key: 'fileName', header: 'File', minWidth: 200, render: (_v, { file }) => (
            <div className="min-w-0">
              <p className="text-body-sm font-medium text-primary-900 dark:text-neutral-50 truncate max-w-[16rem]" title={file.fileName}>{file.fileName}</p>
              <p className="caption">{file.channel === 'INBOX' ? 'Delivery folder' : 'System feed'}</p>
            </div>
          ) },
          { key: 'receivedAt', header: 'Received', minWidth: 150, dropOrder: 2, render: (_v, { file }) => (
            <span className="caption">{formatDateTime(file.receivedAt)}</span>
          ) },
          { key: 'counts', header: 'Statements', minWidth: 170, dropOrder: 1, render: (_v, { file }) => (
            <span className="caption">{file.appliedCount} applied · {file.duplicateCount} duplicate · {file.refusedCount} refused</span>
          ) },
          { key: 'status', header: 'Status', align: 'right', minWidth: 110, render: (_v, f) => (
            <Badge variant={FILE_OUTCOME[fileOutcome(f)].variant} size="sm">{FILE_OUTCOME[fileOutcome(f)].label}</Badge>
          ) },
        ]}
      />
      <Drawer
        isOpen={!!open}
        onClose={() => setOpen(null)}
        title={open?.file.fileName}
        subtitle={open ? `${open.file.channel === 'INBOX' ? 'Delivery folder' : 'System feed'} · ${formatDateTime(open.file.receivedAt)}` : undefined}
      >
        {open && (
          <div className="space-y-2">
            {open.file.error && <p className="caption-error">{open.file.error}</p>}
            {open.statements.length === 0 && !open.file.error && <p className="caption">No statements in this file.</p>}
            {open.statements.map((s) => (
              <div key={s.id} className="p-3 rounded-lg border border-edge-subtle">
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
      </Drawer>
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
