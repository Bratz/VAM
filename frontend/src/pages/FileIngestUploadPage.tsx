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
// Upload control is the CsvAccountUpload precedent: click-only despite
// dropzone styling, plain <input type="file">, no drag-and-drop library.
// ============================================================================

import React, { useEffect, useRef, useState } from 'react';
import { Upload, FileText, X, AlertTriangle, CheckCircle2, RefreshCw } from 'lucide-react';
import { Page } from '../components/layout/Page';
import { Card, CardHeader, Button, Badge, Select, Input } from '../components/ui';
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

const FileIngestUploadPage: React.FC = () => {
  const [domain, setDomain] = useState<IngestDomain>('RECEIVABLES');
  const [customerId, setCustomerId] = useState('DEMO-CUSTOMER-1');
  const [file, setFile] = useState<File | null>(null);
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
          <div className="grid grid-cols-2 gap-4">
            <Select
              label="Domain"
              value={domain}
              onChange={(e) => setDomain(e.target.value as IngestDomain)}
              disabled={!!job}
              options={[
                { value: 'RECEIVABLES', label: 'Receivables' },
                { value: 'PAYABLES', label: 'Payables' },
                { value: 'PAYMENTS', label: 'Payments' },
              ]}
            />
            <Input
              label="Customer ID"
              value={customerId}
              onChange={(e) => setCustomerId(e.target.value)}
              disabled={!!job}
              placeholder="e.g. ACME-CORP-1"
            />
          </div>

          {!job && (
            <div className="border-2 border-dashed border-neutral-200 dark:border-primary-800 rounded-lg p-6 text-center hover:border-primary-300 transition-colors">
              <input type="file" onChange={handleFile} className="hidden" id="ingest-file-upload" />
              <label htmlFor="ingest-file-upload" className="cursor-pointer">
                <Upload className="w-8 h-8 text-neutral-400 dark:text-neutral-500 mx-auto mb-2" />
                <p className="text-sm text-neutral-600 dark:text-neutral-300">Click to choose a file</p>
                <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">Any format — the pipeline figures out its structure</p>
              </label>
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
                  <Badge variant="info">{STAGE_LABELS[job.stage]}</Badge>
                )
              }
            />
            {job.stage === 'BLOCKED' && job.blockedReason && (
              <div className="mb-4 p-3 rounded-lg bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30">
                <p className="text-sm text-error-700 dark:text-error-300">{job.blockedReason}</p>
              </div>
            )}
            <div className="overflow-x-auto py-2">
              <Stepper
                steps={steps.map((s) => ({ id: s, title: STAGE_LABELS[s] }))}
                currentStep={currentStepIndex}
              />
            </div>
          </Card>

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
