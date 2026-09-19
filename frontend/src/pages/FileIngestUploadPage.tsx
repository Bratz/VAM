// ============================================================================
// FileIngestUploadPage — lets a customer upload a payables/receivables/
// payment file in any format and watch it move through the file-ingest
// pipeline (a standard backend feature — see tasks/file-ingest-pipeline-
// design.md's "Revised architecture") via the Stepper, polled every ~3s (no
// WebSocket/SSE exists anywhere in this frontend today — interval polling
// matches the rest of the app). An unrecognized format files a Jira ticket
// for a separate agent worker and the job sits at AWAITING_TRANSFORM until
// that worker (polling Jira on its own schedule, never called directly)
// resolves it and backend's retry sweep notices and resumes.
//
// Title/description register via the shared PageHeader (renders into
// Layout's sticky header, not an in-page <h1>) — matches every other page in
// this app; this page was previously the one outlier hand-rolling its own
// heading. The raw per-event timeline was intentionally dropped (UI review,
// 2026-09) — the Issue Status view below already tells the same story in
// plain language for the one case (a filed ticket) it actually mattered.
//
// Structure borrows ClearTax's broker-statement-upload pattern (tile picker
// for "what is this" -> a real dropzone -> a milestone-style progress view
// -> a stat scorecard on completion) but built entirely from this app's own
// design system (Card/Badge, ink/pacific-cyan palette, Geist) and existing
// data — no new backend endpoints. Drag-and-drop is native HTML5 (no
// library); the DONE-state scorecard parses the numbers already present in
// the DONE timeline event's `detail` string instead of adding an endpoint.
//
// Whenever a ticket actually gets filed (an unrecognized format), the
// horizontal Stepper is swapped for a vertical "Issue Status" timeline —
// modeled on ClearTax's own "Import from Bajaj" issue-resolution screen
// (ticket filed -> named reviewer -> named engineer -> resolved), including
// giving the two backend agents human display names (AGENT_NAMES below —
// purely a frontend label; the backend's own `actor` column is always the
// literal string "system" today, see TimelineEvent.java, so there's no
// per-agent identity to preserve server-side). The reference's countdown
// ("we'll fix this in 09:17") is deliberately NOT copied verbatim — there's
// no real ETA to promise, so this counts elapsed time instead ("working on
// it — 02:14") to stay honest rather than fabricate a deadline.
// ============================================================================

import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Upload, FileText, X, AlertTriangle, CheckCircle2, RefreshCw,
  ArrowDownToLine, ArrowUpFromLine, Send, ScanSearch, Wand2, Hammer,
  ShieldCheck, Zap, Clock, PackageCheck, Workflow, XCircle, PauseCircle,
  Loader2,
} from 'lucide-react';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Card, CardHeader, Button, Badge } from '../components/ui';
import { Stepper } from '../components/ui/enhanced';
import { ingestApi, IngestDomain, IngestJobResponse, IngestStage, RowStatus, StagedRowResponse, TimelineEventResponse } from '../services/ingestApi';
import { formatFileSize } from '../utils';

const POLL_INTERVAL_MS = 3000;

const STAGE_LABELS: Record<IngestStage, string> = {
  RECEIVED: 'Received',
  ANALYZED: 'Analyzed',
  SIGNATURE_NEW: 'New Format',
  CODING_AGENT_RUNNING: 'Building Transform',
  TEST_GATE: 'Testing Transform',
  SIGNATURE_MATCHED: 'Format Recognized',
  AWAITING_TRANSFORM: 'Awaiting Transform',
  STAGED: 'Staged',
  PROCESSING: 'Processing',
  DONE: 'Done',
  BLOCKED: 'Blocked',
};

const STAGE_DESCRIPTIONS: Record<IngestStage, string> = {
  RECEIVED: 'File received',
  ANALYZED: "Reading the file's structure",
  SIGNATURE_NEW: "Haven't seen this exact layout before",
  CODING_AGENT_RUNNING: 'Writing a parser for it',
  TEST_GATE: "Checking the parser's output",
  SIGNATURE_MATCHED: 'Recognized — reusing a known parser',
  AWAITING_TRANSFORM: 'Waiting on format support',
  STAGED: 'Rows staged for review',
  PROCESSING: 'Posting transactions',
  DONE: 'Complete',
  BLOCKED: 'Needs attention',
};

const STAGE_ICONS: Record<IngestStage, React.ReactNode> = {
  RECEIVED: <FileText className="w-4 h-4" />,
  ANALYZED: <ScanSearch className="w-4 h-4" />,
  SIGNATURE_NEW: <Wand2 className="w-4 h-4" />,
  CODING_AGENT_RUNNING: <Hammer className="w-4 h-4" />,
  TEST_GATE: <ShieldCheck className="w-4 h-4" />,
  SIGNATURE_MATCHED: <Zap className="w-4 h-4" />,
  AWAITING_TRANSFORM: <Clock className="w-4 h-4" />,
  STAGED: <PackageCheck className="w-4 h-4" />,
  PROCESSING: <Workflow className="w-4 h-4" />,
  DONE: <CheckCircle2 className="w-4 h-4" />,
  BLOCKED: <AlertTriangle className="w-4 h-4" />,
};

/** Human-facing display names for the two backend agents — a purely cosmetic frontend label,
 * see the file header comment. Change these two strings to rename them. */
const AGENT_NAMES = {
  analysis: 'Asha · Format Analysis',
  coding: 'Rohan · Format Engineering',
};

const DOMAIN_OPTIONS: { value: IngestDomain; label: string; description: string; icon: React.ReactNode }[] = [
  { value: 'RECEIVABLES', label: 'Receivables', description: 'Incoming customer payments', icon: <ArrowDownToLine className="w-5 h-5" /> },
  { value: 'RECEIVABLES_INVOICE', label: 'Raise Invoices', description: 'Bulk-create new invoices awaiting payment', icon: <FileText className="w-5 h-5" /> },
  { value: 'PAYABLES', label: 'Payables', description: 'Outgoing vendor payments', icon: <ArrowUpFromLine className="w-5 h-5" /> },
  { value: 'PAYMENTS', label: 'Payments', description: 'Outgoing payment instructions', icon: <Send className="w-5 h-5" /> },
];

/** The pipeline has three possible shapes depending on what actually happens for this job's
 * format — a known Receivables shape skips escalation entirely; anything else files a ticket
 * (AWAITING_TRANSFORM) for the separate agent worker, which then reports back either
 * SIGNATURE_MATCHED (a cache hit) or SIGNATURE_NEW/CODING_AGENT_RUNNING/TEST_GATE (a genuine
 * first sighting) before backend's retry sweep resumes staging/processing. Which branch applies
 * isn't knowable up front, so this infers it from whichever stage actually shows up first in the
 * polled timeline, defaulting to the simplest (known-shape) sequence until there's evidence
 * otherwise. */
function stepsForEvents(events: TimelineEventResponse[]): IngestStage[] {
  const stages = new Set(events.map((e) => e.stage));
  if (stages.has('SIGNATURE_MATCHED')) {
    return ['RECEIVED', 'AWAITING_TRANSFORM', 'ANALYZED', 'SIGNATURE_MATCHED', 'STAGED', 'PROCESSING', 'DONE'];
  }
  if (stages.has('SIGNATURE_NEW') || stages.has('CODING_AGENT_RUNNING') || stages.has('TEST_GATE') || stages.has('AWAITING_TRANSFORM')) {
    return ['RECEIVED', 'AWAITING_TRANSFORM', 'ANALYZED', 'SIGNATURE_NEW', 'CODING_AGENT_RUNNING', 'TEST_GATE', 'STAGED', 'PROCESSING', 'DONE'];
  }
  return ['RECEIVED', 'STAGED', 'PROCESSING', 'DONE'];
}

type IssueStepStatus = 'done' | 'active' | 'blocked' | 'pending';

interface IssueStep {
  key: string;
  title: string;
  subtitle?: string;
  status: IssueStepStatus;
  timestamp?: string;
}

/** Builds the 4-step "Issue Status" timeline for a job that actually filed a ticket (known
 * format or not — a cache hit just resolves steps 2-3 near-instantly). Mirrors the shape of
 * ClearTax's own issue-resolution screen: ticket filed -> a named reviewer -> a named engineer
 * -> resolved, each derived from real timeline events rather than invented checklist copy. */
function buildIssueSteps(job: IngestJobResponse, timeline: TimelineEventResponse[]): IssueStep[] {
  const find = (stage: IngestStage, status: string) => timeline.find((e) => e.stage === stage && e.status === status);

  const filed = find('AWAITING_TRANSFORM', 'COMPLETE');
  const analyzed = find('ANALYZED', 'COMPLETE');
  const testGate = find('TEST_GATE', 'COMPLETE');
  const signatureMatched = find('SIGNATURE_MATCHED', 'COMPLETE');
  const codingStarted = find('CODING_AGENT_RUNNING', 'STARTED');
  const staged = find('STAGED', 'COMPLETE');

  const engineeringDone = !!testGate || !!signatureMatched;
  const engineeringActive = !engineeringDone && (!!codingStarted || !!analyzed);
  const processingDone = job.stage === 'STAGED' || job.stage === 'PROCESSING' || job.stage === 'DONE';

  // Nothing is genuinely "in progress" once the job is BLOCKED — whichever step was active when
  // it failed should read as stopped, not still-pulsing (confirmed visually: without this, the
  // step that failed kept showing the same amber "working on it" animation as a live job).
  const activeStatus: IssueStepStatus = job.stage === 'BLOCKED' ? 'blocked' : 'active';

  return [
    {
      key: 'filed',
      title: 'Ticket filed for this format',
      status: filed ? 'done' : 'pending',
      timestamp: filed?.occurredAt,
    },
    {
      key: 'review',
      title: `${AGENT_NAMES.analysis} is reviewing your file`,
      subtitle: analyzed?.detail ?? (filed ? "Reading the file's structure…" : undefined),
      status: analyzed ? 'done' : filed ? activeStatus : 'pending',
      timestamp: analyzed?.occurredAt,
    },
    {
      key: 'engineer',
      title: signatureMatched
        ? `${AGENT_NAMES.analysis} recognized this format`
        : `${AGENT_NAMES.coding} builds support for this format`,
      subtitle: testGate?.detail ?? (engineeringActive ? 'Writing and testing a parser for this layout…' : undefined),
      status: engineeringDone ? 'done' : engineeringActive ? activeStatus : 'pending',
      timestamp: (testGate ?? signatureMatched)?.occurredAt,
    },
    {
      key: 'process',
      title: 'File gets staged and processed',
      subtitle: job.stage === 'DONE' ? 'Complete' : engineeringDone ? 'Posting your transactions…' : undefined,
      status: processingDone ? 'done' : engineeringDone ? activeStatus : 'pending',
      timestamp: staged?.occurredAt,
    },
  ];
}

function formatElapsed(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const mm = String(Math.floor(s / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

const IssueStatusTimeline: React.FC<{ steps: IssueStep[]; elapsedLabel: { text: string; tone: 'live' | 'success' | 'error' } | null }> = ({ steps, elapsedLabel }) => (
  <div>
    {elapsedLabel && (
      <div className={`mb-5 flex items-center justify-center gap-2 rounded-full py-2 text-body-sm font-medium ${
        elapsedLabel.tone === 'live'
          ? 'bg-success-50 text-success-700 dark:bg-success-500/15 dark:text-success-300'
          : elapsedLabel.tone === 'error'
            ? 'bg-error-50 text-error-700 dark:bg-error-500/15 dark:text-error-300'
            : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300'
      }`}>
        {elapsedLabel.tone === 'live' && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
        {elapsedLabel.text}
      </div>
    )}
    <div className="space-y-0">
      {steps.map((step, idx) => (
        <div key={step.key} className="flex gap-3">
          <div className="flex flex-col items-center">
            <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 ${
              step.status === 'done'
                ? 'bg-success-500 text-white'
                : step.status === 'active'
                  ? 'border-2 border-warning-500 text-warning-600 dark:text-warning-300'
                  : step.status === 'blocked'
                    ? 'bg-error-500 text-white'
                    : 'border-2 border-neutral-200 text-neutral-400 dark:border-primary-700'
            }`}>
              {step.status === 'done' ? (
                <CheckCircle2 className="w-4 h-4" />
              ) : step.status === 'active' ? (
                <span className="w-2 h-2 rounded-full bg-warning-500 animate-pulse-soft" />
              ) : step.status === 'blocked' ? (
                <XCircle className="w-4 h-4" />
              ) : (
                <span className="text-caption font-semibold">{idx + 1}</span>
              )}
            </div>
            {idx < steps.length - 1 && (
              <div className={`w-0.5 flex-1 min-h-[1.5rem] ${step.status === 'done' ? 'bg-success-500' : 'bg-neutral-200 dark:bg-primary-800'}`} />
            )}
          </div>
          <div className="pb-5 min-w-0">
            <p className={`text-body-sm ${step.status === 'pending' ? 'text-neutral-400 dark:text-neutral-500' : 'font-semibold text-primary-900 dark:text-neutral-50'}`}>
              {step.title}
            </p>
            {step.subtitle && (
              <p className="caption mt-0.5">{step.subtitle}</p>
            )}
            {step.timestamp && (
              <p className="caption mt-0.5">{new Date(step.timestamp).toLocaleString()}</p>
            )}
          </div>
        </div>
      ))}
    </div>
  </div>
);

const ROW_STATUS_BADGE: Record<RowStatus, { variant: 'success' | 'error' | 'warning' | 'neutral'; label: string }> = {
  PROCESSED: { variant: 'success', label: 'Processed' },
  FAILED: { variant: 'error', label: 'Failed' },
  QUARANTINED: { variant: 'warning', label: 'Quarantined' },
  READY: { variant: 'neutral', label: 'Ready' },
  STAGED: { variant: 'neutral', label: 'Staged' },
};

/** Rows only exist once staging has actually happened — fetching earlier would just 404/empty. */
function hasRows(stage: IngestStage): boolean {
  return stage === 'STAGED' || stage === 'PROCESSING' || stage === 'DONE';
}

/** Parses IngestOrchestrator's own DONE-summary string ("%d processed, %d quarantined, %d
 * failed (of %d total).") into a scorecard instead of adding a dedicated summary endpoint —
 * the numbers already exist, just inside prose. */
function parseDoneSummary(events: TimelineEventResponse[]): { processed: number; quarantined: number; failed: number; total: number } | null {
  const done = events.find((e) => e.stage === 'DONE');
  const match = done?.detail?.match(/(\d+) processed, (\d+) quarantined, (\d+) failed \(of (\d+) total\)/);
  if (!match) return null;
  const [, processed, quarantined, failed, total] = match;
  return { processed: Number(processed), quarantined: Number(quarantined), failed: Number(failed), total: Number(total) };
}

const FileIngestUploadPage: React.FC = () => {
  const [domain, setDomain] = useState<IngestDomain>('RECEIVABLES');
  const [customerId, setCustomerId] = useState('');
  const [corporates, setCorporates] = useState<{ id: string; legalName?: string; tradeName?: string }[]>([]);
  const [corporatesLoading, setCorporatesLoading] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [job, setJob] = useState<IngestJobResponse | null>(null);
  const [timeline, setTimeline] = useState<TimelineEventResponse[]>([]);
  const [rows, setRows] = useState<StagedRowResponse[]>([]);
  const [history, setHistory] = useState<IngestJobResponse[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  useEffect(() => stopPolling, []);

  // Corporate picker options — same inline-fetch pattern every corporate-scoped page uses today
  // (no shared corporateApi service exists in this codebase).
  useEffect(() => {
    setCorporatesLoading(true);
    fetch('/api/v1/corporates')
      .then((r) => r.json())
      .then((result) => setCorporates(result.data || result || []))
      .catch(() => setCorporates([]))
      .finally(() => setCorporatesLoading(false));
  }, []);

  // Recent-uploads list — only worth fetching while there's no active job to look at.
  useEffect(() => {
    if (job) return;
    ingestApi.listJobs().then(setHistory).catch(() => {
      // A failed history fetch just leaves the list empty; the upload form itself still works.
    });
  }, [job]);

  const poll = async (jobId: string) => {
    try {
      const [latestJob, latestTimeline] = await Promise.all([
        ingestApi.getJob(jobId),
        ingestApi.getTimeline(jobId),
      ]);
      setJob(latestJob);
      setTimeline(latestTimeline);
      if (hasRows(latestJob.stage)) {
        ingestApi.getRows(jobId).then(setRows).catch(() => {});
      }
      if (latestJob.stage === 'DONE' || latestJob.stage === 'BLOCKED') {
        stopPolling();
      }
    } catch {
      // A transient poll failure isn't fatal — the next tick tries again.
      // Stopping here would strand the UI on a stale stage forever.
    }
  };

  const handleSelectPastJob = async (pastJob: IngestJobResponse) => {
    stopPolling();
    setJob(pastJob);
    setRows([]);
    const jobTimeline = await ingestApi.getTimeline(pastJob.id).catch(() => []);
    setTimeline(jobTimeline);
    if (hasRows(pastJob.stage)) {
      ingestApi.getRows(pastJob.id).then(setRows).catch(() => {});
    }
    // Rare case: clicking into a job that's still mid-flight (e.g. opened in another tab) —
    // resume polling exactly like a fresh upload would.
    if (pastJob.stage !== 'DONE' && pastJob.stage !== 'BLOCKED') {
      pollRef.current = setInterval(() => poll(pastJob.id), POLL_INTERVAL_MS);
    }
  };

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0];
    e.target.value = '';
    if (selected) setFile(selected);
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) setFile(dropped);
  };

  const handleUpload = async () => {
    if (!file || !customerId.trim()) return;
    setUploading(true);
    setUploadError(null);
    try {
      const created = await ingestApi.upload(domain, customerId.trim(), file);
      setJob(created);
      setTimeline([]);
      stopPolling();
      pollRef.current = setInterval(() => poll(created.id), POLL_INTERVAL_MS);
      await poll(created.id);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const handleReset = () => {
    stopPolling();
    setJob(null);
    setTimeline([]);
    setRows([]);
    setFile(null);
    setUploadError(null);
  };

  const steps = job ? stepsForEvents(timeline) : [];
  const seenStageIndexes = timeline.map((e) => steps.indexOf(e.stage)).filter((i) => i >= 0);
  const currentStepIndex = job
    ? job.stage === 'BLOCKED'
      ? Math.max(0, ...seenStageIndexes)
      : Math.max(steps.indexOf(job.stage), 0)
    : 0;

  const isLive = !!job && job.stage !== 'DONE' && job.stage !== 'BLOCKED';
  const doneSummary = useMemo(() => (job?.stage === 'DONE' ? parseDoneSummary(timeline) : null), [job?.stage, timeline]);

  // A ticket was filed -> render the ClearTax-style "Issue Status" timeline instead of the plain
  // Stepper (a known-shape upload never files a ticket, so it keeps the simpler Stepper).
  const hasTicket = !!job?.jiraTicketKey;
  const issueSteps = useMemo(() => (job && hasTicket ? buildIssueSteps(job, timeline) : []), [job, hasTicket, timeline]);

  useEffect(() => {
    if (!isLive || !hasTicket) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [isLive, hasTicket]);

  const elapsedLabel = useMemo(() => {
    if (!job || !hasTicket) return null;
    const filedAt = timeline.find((e) => e.stage === 'AWAITING_TRANSFORM' && e.status === 'COMPLETE')?.occurredAt ?? job.createdAt;
    const startMs = new Date(filedAt).getTime();
    if (isLive) {
      return { text: `Working on it — ${formatElapsed((now - startMs) / 1000)} elapsed`, tone: 'live' as const };
    }
    const endEvent = [...timeline].reverse().find((e) => e.stage === job.stage);
    const endMs = endEvent ? new Date(endEvent.occurredAt).getTime() : now;
    if (job.stage === 'DONE') {
      return { text: `Resolved in ${formatElapsed((endMs - startMs) / 1000)}`, tone: 'success' as const };
    }
    if (job.stage === 'BLOCKED') {
      return { text: `Escalated after ${formatElapsed((endMs - startMs) / 1000)}`, tone: 'error' as const };
    }
    return null;
  }, [job, hasTicket, isLive, now, timeline]);

  return (
    <Page maxWidth="narrow">
      <PageHeader
        title="File Ingest Pipeline"
        description="Upload a payables/receivables/payment file in any format — an analysis agent profiles it, a coding agent builds a transform for shapes it hasn't seen before, and the result is reconciled, staged and processed automatically."
      />

      <Card>
        <CardHeader title="New Upload" subtitle="Every field below stays editable until you upload." />
        <div className="space-y-4">
          <div>
            <label className="block text-body-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">What are you uploading?</label>
            <div className="grid grid-cols-3 gap-3">
              {DOMAIN_OPTIONS.map((opt) => {
                const selected = domain === opt.value;
                return (
                  <Card
                    key={opt.value}
                    padding="sm"
                    interactive={!job}
                    onClick={() => !job && setDomain(opt.value)}
                    className={
                      selected
                        ? 'border-accent-500 ring-1 ring-accent-500 dark:border-accent-500'
                        : job
                          ? 'opacity-60'
                          : ''
                    }
                  >
                    <div className={`w-9 h-9 rounded-lg flex items-center justify-center mb-2 ${selected ? 'bg-accent-500 text-white' : 'bg-neutral-100 text-neutral-500 dark:bg-primary-800 dark:text-neutral-300'}`}>
                      {opt.icon}
                    </div>
                    <p className="body-strong font-semibold">{opt.label}</p>
                    <p className="caption mt-0.5">{opt.description}</p>
                  </Card>
                );
              })}
            </div>
          </div>

          <ScopeSelector
            mode="corporate-only"
            corporates={corporates}
            selectedCorporateId={customerId}
            onCorporateChange={(id) => setCustomerId(id || '')}
            loading={corporatesLoading}
          />

          {!job && (
            <div
              onDragOver={(e) => { e.preventDefault(); setIsDragging(true); }}
              onDragLeave={(e) => { e.preventDefault(); setIsDragging(false); }}
              onDrop={handleDrop}
              className={`border-2 border-dashed rounded-lg p-8 text-center transition-all duration-200 ${
                isDragging
                  ? 'border-accent-500 bg-accent-50/60 dark:bg-accent-500/10 scale-[1.01]'
                  : 'border-neutral-200 dark:border-primary-800 hover:border-primary-300'
              }`}
            >
              <input
                type="file"
                accept=".csv,.txt,.tsv,.xlsx,.xls,.xml,.dat"
                onChange={handleFile}
                className="hidden"
                id="ingest-file-upload"
              />
              <Upload className={`w-8 h-8 mx-auto mb-2 transition-transform ${isDragging ? 'text-accent-500 scale-110' : 'text-neutral-400 dark:text-neutral-500'}`} />
              <p className="body-sm">
                Drop your file here, or{' '}
                <label htmlFor="ingest-file-upload" className="text-accent-600 dark:text-accent-300 font-medium cursor-pointer hover:underline">
                  browse to upload
                </label>
              </p>
              <p className="caption mt-1">Any format — the pipeline figures out its structure</p>
            </div>
          )}

          {uploadError && (
            <p className="caption-error flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" /> {uploadError}
            </p>
          )}
        </div>
      </Card>

      {/* Summary confirmation panel — same pattern as CreateReceivablePage's review sidebar
          (key/value recap, a divider, then a warning-or-success readiness banner) adapted to
          this page's single-column, much shorter form: shown once a file is picked (the dropzone
          itself already prompts for that first step) rather than from first render. */}
      {!job && file && (
        <Card>
          <p className="label mb-3">Summary</p>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="caption">Uploading</span>
              <span className="body-strong">
                {DOMAIN_OPTIONS.find((o) => o.value === domain)?.label}
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="caption">Customer</span>
              <span className="body-strong">{customerId.trim() || '—'}</span>
            </div>
            <div className="flex items-center justify-between pt-3 border-t border-neutral-100 dark:border-primary-800/60">
              <span className="caption">File</span>
              <div className="flex items-center gap-2 min-w-0">
                <FileText className="w-3.5 h-3.5 shrink-0 text-neutral-400" />
                <span className="text-body-sm font-medium text-primary-900 dark:text-neutral-50 truncate max-w-[12rem]">{file.name}</span>
                <button onClick={() => setFile(null)} className="p-0.5 rounded hover:bg-neutral-200 dark:hover:bg-primary-800 shrink-0">
                  <X className="w-3.5 h-3.5 text-neutral-400" />
                </button>
              </div>
            </div>
            <p className="caption text-right">{formatFileSize(file.size)}</p>
          </div>
        </Card>
      )}

      {!job && file && (
        customerId.trim() ? (
          <div className="p-3 rounded-lg bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 flex items-start gap-2">
            <CheckCircle2 className="w-4 h-4 text-success-600 dark:text-success-300 shrink-0 mt-0.5" />
            <div>
              <p className="text-body-sm font-medium text-success-800 dark:text-success-300">Ready to upload</p>
              <p className="text-caption text-success-700 dark:text-success-300 mt-0.5">
                The pipeline starts analyzing this file the moment you upload it.
              </p>
            </div>
          </div>
        ) : (
          <div className="p-3 rounded-lg bg-warning-50 dark:bg-warning-500/10 border border-warning-200 dark:border-warning-500/30 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300 shrink-0 mt-0.5" />
            <div>
              <p className="text-body-sm font-medium text-warning-800 dark:text-warning-300">Complete required fields</p>
              <p className="text-caption text-warning-700 dark:text-warning-300 mt-0.5">Enter a customer ID above to continue.</p>
            </div>
          </div>
        )
      )}

      {!job ? (
        <Button
          variant="primary"
          fullWidth
          loading={uploading}
          disabled={!file || !customerId.trim()}
          leftIcon={<Upload className="w-4 h-4" />}
          onClick={handleUpload}
        >
          Upload and start pipeline
        </Button>
      ) : (
        <Button variant="outline" fullWidth leftIcon={<RefreshCw className="w-4 h-4" />} onClick={handleReset}>
          Upload another file
        </Button>
      )}

      {!job && history.length > 0 && (
        <Card>
          <CardHeader title="Recent Uploads" subtitle="Pick one to see its record-by-record status." />
          <div className="space-y-1 -mx-2">
            {history.map((pastJob) => (
              <button
                key={pastJob.id}
                onClick={() => handleSelectPastJob(pastJob)}
                className="w-full flex items-center justify-between gap-3 px-2 py-2 rounded-lg text-left hover:bg-neutral-50 dark:hover:bg-primary-950 transition-colors"
              >
                <div className="flex items-center gap-2 min-w-0">
                  <FileText className="w-4 h-4 shrink-0 text-neutral-400" />
                  <div className="min-w-0">
                    <p className="text-body-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{pastJob.originalFilename}</p>
                    <p className="caption">
                      {pastJob.domain} · {pastJob.customerId} · {new Date(pastJob.createdAt).toLocaleString()}
                    </p>
                  </div>
                </div>
                {pastJob.stage === 'DONE' ? (
                  <Badge variant="success" size="sm">Done</Badge>
                ) : pastJob.stage === 'BLOCKED' ? (
                  <Badge variant="error" size="sm">Blocked</Badge>
                ) : (
                  <Badge variant="info" size="sm">{STAGE_LABELS[pastJob.stage]}</Badge>
                )}
              </button>
            ))}
          </div>
        </Card>
      )}

      {job && (
        <>
          <Card>
            <CardHeader
              title={job.originalFilename}
              subtitle={`${job.domain} · ${job.customerId}${job.jiraTicketKey ? ` · ${job.jiraTicketKey}` : ''}`}
              action={
                job.stage === 'DONE' ? (
                  <Badge variant="success" icon={<CheckCircle2 className="w-3 h-3" />}>Done</Badge>
                ) : job.stage === 'BLOCKED' ? (
                  <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>Blocked</Badge>
                ) : (
                  <Badge variant="info" dot className="animate-pulse-soft">{STAGE_LABELS[job.stage]}</Badge>
                )
              }
            />
            {job.stage === 'BLOCKED' && job.blockedReason && (
              <div className="mb-4 p-3 rounded-lg bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 flex items-start gap-2">
                <PauseCircle className="w-4 h-4 text-error-500 mt-0.5 shrink-0" />
                <p className="text-body-sm text-error-700 dark:text-error-300">{job.blockedReason}</p>
              </div>
            )}
            {hasTicket ? (
              <IssueStatusTimeline steps={issueSteps} elapsedLabel={elapsedLabel} />
            ) : (
              <div className="overflow-x-auto py-2">
                <Stepper
                  steps={steps.map((s) => ({
                    id: s,
                    title: STAGE_LABELS[s],
                    description: STAGE_DESCRIPTIONS[s],
                    icon: STAGE_ICONS[s],
                  }))}
                  currentStep={currentStepIndex}
                />
              </div>
            )}
            {isLive && (
              <p className="caption text-center mt-1 flex items-center justify-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-accent-500 animate-pulse-soft" />
                Updating automatically
              </p>
            )}
          </Card>

          {doneSummary && (
            <div className="grid grid-cols-4 gap-3">
              <Card padding="sm" className="text-center">
                <p className="caption">Total rows</p>
                <p className="text-heading-sm font-semibold text-primary-900 dark:text-neutral-50 mt-0.5">{doneSummary.total}</p>
              </Card>
              <Card padding="sm" className="text-center">
                <CheckCircle2 className="w-4 h-4 text-success-500 mx-auto" />
                <p className="text-heading-sm font-semibold text-success-700 dark:text-success-300 mt-0.5">{doneSummary.processed}</p>
                <p className="caption">Processed</p>
              </Card>
              <Card padding="sm" className="text-center">
                <AlertTriangle className="w-4 h-4 text-warning-500 mx-auto" />
                <p className="text-heading-sm font-semibold text-warning-700 dark:text-warning-300 mt-0.5">{doneSummary.quarantined}</p>
                <p className="caption">Quarantined</p>
              </Card>
              <Card padding="sm" className="text-center">
                <XCircle className="w-4 h-4 text-error-500 mx-auto" />
                <p className="text-heading-sm font-semibold text-error-700 dark:text-error-300 mt-0.5">{doneSummary.failed}</p>
                <p className="caption">Failed</p>
              </Card>
            </div>
          )}

          {rows.length > 0 && (
            <Card padding="none">
              <div className="p-6 pb-0">
                <CardHeader title="Rows" subtitle={`${rows.length} row(s) from ${job.originalFilename}.`} />
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-body-sm">
                  <thead>
                    <tr className="border-t border-neutral-100 dark:border-primary-800/60 caption">
                      <th className="text-left font-medium px-6 py-2">Row</th>
                      <th className="text-left font-medium px-3 py-2">Status</th>
                      <th className="text-right font-medium px-3 py-2">Amount</th>
                      <th className="text-left font-medium px-3 py-2">Account</th>
                      <th className="text-left font-medium px-6 py-2">Reason</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.sourceRowNumber} className="border-t border-neutral-100 dark:border-primary-800/60">
                        <td className="px-6 py-2 text-neutral-500 dark:text-neutral-400 font-mono text-caption">{row.sourceRowNumber}</td>
                        <td className="px-3 py-2">
                          <Badge variant={ROW_STATUS_BADGE[row.status].variant} size="sm">{ROW_STATUS_BADGE[row.status].label}</Badge>
                        </td>
                        <td className="px-3 py-2 text-right text-primary-900 dark:text-neutral-50 font-mono">
                          {row.amount != null ? `${row.amount} ${row.currency ?? ''}` : '—'}
                        </td>
                        <td className="px-3 py-2 text-neutral-600 dark:text-neutral-300 font-mono text-caption truncate max-w-[10rem]">
                          {row.targetAccountReference ?? '—'}
                        </td>
                        <td className="px-6 py-2 caption">{row.reason ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}
        </>
      )}
    </Page>
  );
};

export default FileIngestUploadPage;
