// ============================================================================
// File-ingest pipeline client. The standard upload/staging/processing path
// is now a normal backend feature (see tasks/file-ingest-pipeline-design.md's
// "Revised architecture") — this reuses the shared apiClient from ./api
// rather than a separate axios instance/base URL, the same way any other
// backend feature's client would. Endpoints return plain JSON (no
// ApiResponse{success,data} envelope, unlike most of backend's other
// controllers) — see FileIngestController.
// ============================================================================

import { apiClient } from './api';

export type IngestDomain = 'RECEIVABLES' | 'RECEIVABLES_INVOICE' | 'PAYABLES' | 'PAYMENTS';

// Mirrors IngestStage.java. Not a single linear sequence — SIGNATURE_NEW ->
// CODING_AGENT_RUNNING -> TEST_GATE only fires on a cache MISS, SIGNATURE_MATCHED
// only on a HIT (see FileIngestUploadPage's stepsForJob, which branches on
// whichever of those two actually shows up in the polled timeline).
// AWAITING_TRANSFORM is the new decoupled-architecture stage: a ticket has been
// filed for the separate agent worker and this app is waiting for it to finish.
export type IngestStage =
  | 'RECEIVED'
  | 'ANALYZED'
  | 'SIGNATURE_NEW'
  | 'CODING_AGENT_RUNNING'
  | 'TEST_GATE'
  | 'SIGNATURE_MATCHED'
  | 'AWAITING_TRANSFORM'
  | 'STAGED'
  | 'PROCESSING'
  | 'DONE'
  | 'BLOCKED';

export interface IngestJobResponse {
  id: string;
  customerId: string;
  domain: IngestDomain;
  originalFilename: string;
  stage: IngestStage;
  jiraTicketKey: string | null;
  blockedReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TimelineEventResponse {
  stage: IngestStage;
  status: string;
  detail: string | null;
  actor: string;
  occurredAt: string;
}

// Mirrors RowStatus.java.
export type RowStatus = 'STAGED' | 'READY' | 'QUARANTINED' | 'PROCESSED' | 'FAILED';

export interface StagedRowResponse {
  sourceRowNumber: number;
  status: RowStatus;
  reason: string | null;
  amount: number | null;
  currency: string | null;
  targetAccountReference: string | null;
  processedEntityId: string | null;
}

export const ingestApi = {
  listJobs: async (): Promise<IngestJobResponse[]> => {
    const res = await apiClient.get<IngestJobResponse[]>('/ingest/jobs');
    return res.data;
  },

  getRows: async (jobId: string): Promise<StagedRowResponse[]> => {
    const res = await apiClient.get<StagedRowResponse[]>(`/ingest/jobs/${jobId}/rows`);
    return res.data;
  },

  upload: async (domain: IngestDomain, customerId: string, file: File): Promise<IngestJobResponse> => {
    const formData = new FormData();
    formData.append('file', file);
    const res = await apiClient.post<IngestJobResponse>(
      `/ingest/${domain}/upload`,
      formData,
      { params: { customerId }, headers: { 'Content-Type': 'multipart/form-data' } }
    );
    return res.data;
  },

  getJob: async (jobId: string): Promise<IngestJobResponse> => {
    const res = await apiClient.get<IngestJobResponse>(`/ingest/jobs/${jobId}`);
    return res.data;
  },

  getTimeline: async (jobId: string): Promise<TimelineEventResponse[]> => {
    const res = await apiClient.get<TimelineEventResponse[]>(`/ingest/jobs/${jobId}/timeline`);
    return res.data;
  },
};
