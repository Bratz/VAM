// ============================================================================
// FileIngestUploadPage — lets a customer upload a payables/receivables/
// payment file in any format and watch it move through the file-ingest
// pipeline (a standard backend feature — see tasks/file-ingest-pipeline-
// design.md's "Revised architecture") via the Stepper + EventTimeline
// components, polled every ~3s (no WebSocket/SSE exists anywhere in this
// frontend today — interval polling matches the rest of the app). An
// unrecognized format files a Jira ticket for a separate agent worker and
// the job sits at AWAITING_TRANSFORM until that worker (polling Jira on its
// own schedule, never called directly) resolves it and backend's retry
// sweep notices and resumes.
//
// Structure borrows ClearTax's broker-statement-upload pattern (tile picker
// for "what is this" -> a real dropzone -> a milestone-style progress view
// -> a stat scorecard on completion) but built entirely from this app's own
// design system (Card/Badge, ink/pacific-cyan palette, Geist) and existing
// data — no new backend endpoints. Drag-and-drop is native HTML5 (no
// library); the DONE-state scorecard parses the numbers already present in
// the DONE timeline event's `detail` string instead of adding an endpoint.
// ============================================================================

import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Upload, FileText, X, AlertTriangle, CheckCircle2, RefreshCw,
  ArrowDownToLine, ArrowUpFromLine, Send, ScanSearch, Wand2, Hammer,
  ShieldCheck, Zap, Clock, PackageCheck, Workflow, XCircle, PauseCircle,
} from 'lucide-react';
import { Page } from '../components/layout/Page';
import { Card, CardHeader, Button, Badge, Input } from '../components/ui';
import { Stepper } from '../components/ui/enhanced';
import { EventTimeline, TimelineEntry } from '../components/ui/EventTimeline';
import { ingestApi, IngestDomain, IngestJobResponse, IngestStage, TimelineEventResponse } from '../services/ingestApi';

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

const DOMAIN_OPTIONS: { value: IngestDomain; label: string; description: string; icon: React.ReactNode }[] = [
  { value: 'RECEIVABLES', label: 'Receivables', description: 'Incoming customer payments', icon: <ArrowDownToLine className="w-5 h-5" /> },
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

function toTimelineEntries(events: TimelineEventResponse[]): TimelineEntry[] {
  return events.map((e) => ({
    timestamp: e.occurredAt,
    action: `${STAGE_LABELS[e.stage] ?? e.stage} — ${e.status}`,
    actor: e.actor,
    details: e.detail ?? undefined,
  }));
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
  const [customerId, setCustomerId] = useState('DEMO-CUSTOMER-1');
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [job, setJob] = useState<IngestJobResponse | null>(null);
  const [timeline, setTimeline] = useState<TimelineEventResponse[]>([]);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  useEffect(() => stopPolling, []);

  const poll = async (jobId: string) => {
    try {
      const [latestJob, latestTimeline] = await Promise.all([
        ingestApi.getJob(jobId),
        ingestApi.getTimeline(jobId),
      ]);
      setJob(latestJob);
      setTimeline(latestTimeline);
      if (latestJob.stage === 'DONE' || latestJob.stage === 'BLOCKED') {
        stopPolling();
      }
    } catch {
      // A transient poll failure isn't fatal — the next tick tries again.
      // Stopping here would strand the UI on a stale stage forever.
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

  return (
    <Page maxWidth="narrow">
      <div>
        <h1 className="text-2xl font-semibold text-primary-900 dark:text-neutral-50 tracking-tight">File Ingest Pipeline</h1>
        <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">
          Upload a payables/receivables/payment file in any format — an analysis agent profiles it,
          a coding agent builds a transform for shapes it hasn't seen before, and the result is
          reconciled, staged and processed automatically.
        </p>
      </div>

      <Card>
        <CardHeader title="New Upload" subtitle="Every field below stays editable until you upload." />
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">What are you uploading?</label>
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
                    <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{opt.label}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">{opt.description}</p>
                  </Card>
                );
              })}
            </div>
          </div>

          <Input
            label="Customer ID"
            value={customerId}
            onChange={(e) => setCustomerId(e.target.value)}
            disabled={!!job}
            placeholder="e.g. ACME-CORP-1"
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
              <input type="file" onChange={handleFile} className="hidden" id="ingest-file-upload" />
              <Upload className={`w-8 h-8 mx-auto mb-2 transition-transform ${isDragging ? 'text-accent-500 scale-110' : 'text-neutral-400 dark:text-neutral-500'}`} />
              <p className="text-sm text-neutral-600 dark:text-neutral-300">
                Drop your file here, or{' '}
                <label htmlFor="ingest-file-upload" className="text-accent-600 dark:text-accent-400 font-medium cursor-pointer hover:underline">
                  browse to upload
                </label>
              </p>
              <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">Any format — the pipeline figures out its structure</p>
            </div>
          )}

          {!job && file && (
            <div className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded-lg">
              <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 min-w-0">
                <FileText className="w-4 h-4 shrink-0" />
                <span className="truncate">{file.name}</span>
              </div>
              <button onClick={() => setFile(null)} className="p-1 rounded hover:bg-neutral-200 dark:hover:bg-primary-800 shrink-0">
                <X className="w-3.5 h-3.5 text-neutral-400" />
              </button>
            </div>
          )}

          {uploadError && (
            <p className="text-xs text-error-600 dark:text-error-400 flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" /> {uploadError}
            </p>
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
        </div>
      </Card>

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
                  <Badge variant="accent" dot className="animate-pulse-soft">{STAGE_LABELS[job.stage]}</Badge>
                )
              }
            />
            {job.stage === 'BLOCKED' && job.blockedReason && (
              <div className="mb-4 p-3 rounded-lg bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 flex items-start gap-2">
                <PauseCircle className="w-4 h-4 text-error-500 mt-0.5 shrink-0" />
                <p className="text-sm text-error-700 dark:text-error-300">{job.blockedReason}</p>
              </div>
            )}
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
            {isLive && (
              <p className="text-xs text-neutral-400 dark:text-neutral-500 text-center mt-1 flex items-center justify-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-accent-500 animate-pulse-soft" />
                Updating automatically
              </p>
            )}
          </Card>

          {doneSummary && (
            <div className="grid grid-cols-4 gap-3">
              <Card padding="sm" className="text-center">
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Total rows</p>
                <p className="text-xl font-semibold text-primary-900 dark:text-neutral-50 mt-0.5">{doneSummary.total}</p>
              </Card>
              <Card padding="sm" className="text-center">
                <CheckCircle2 className="w-4 h-4 text-success-500 mx-auto" />
                <p className="text-xl font-semibold text-success-700 dark:text-success-300 mt-0.5">{doneSummary.processed}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Processed</p>
              </Card>
              <Card padding="sm" className="text-center">
                <AlertTriangle className="w-4 h-4 text-warning-500 mx-auto" />
                <p className="text-xl font-semibold text-warning-700 dark:text-warning-300 mt-0.5">{doneSummary.quarantined}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Quarantined</p>
              </Card>
              <Card padding="sm" className="text-center">
                <XCircle className="w-4 h-4 text-error-500 mx-auto" />
                <p className="text-xl font-semibold text-error-700 dark:text-error-300 mt-0.5">{doneSummary.failed}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Failed</p>
              </Card>
            </div>
          )}

          <Card>
            <CardHeader title="Timeline" subtitle="Updates automatically every few seconds." />
            {timeline.length === 0 ? (
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Waiting for the first event…</p>
            ) : (
              <EventTimeline entries={toTimelineEntries(timeline)} />
            )}
          </Card>
        </>
      )}
    </Page>
  );
};

export default FileIngestUploadPage;
