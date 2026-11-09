// Customer direct-debit mandates and collection runs. Backend: CollectionsController at /api/v1/collections.
// Uses the {success,data} envelope; the API omits null fields, so optional values are typed optional.

import { apiClient, ApiResponse } from './api';

export type MandateType = 'ONE_OFF' | 'RECURRING';
export type MandatePeriod = 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR';
export type MandateStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'CANCELLED' | 'USED';
export type MandateSource = 'PORTAL' | 'FILE' | 'API' | 'EMANDATE';

export interface Mandate {
  id: string;
  mandateReference: string;
  customerPartyId: string;
  customerName?: string;
  debtorName?: string;
  debtorIban: string;
  debtorBic?: string;
  creditorVaId: string;
  creditorAccount?: string;
  schemeCode: string;
  currency: string;
  mandateType: MandateType;
  validFrom: string;
  validTo?: string;
  signedDate?: string;
  maxPerCollection?: number;
  frequencyCount?: number;
  frequencyPeriod?: MandatePeriod;
  status: MandateStatus;
  statusReason?: string;
  source: MandateSource;
  /** Past its valid-to date. */
  expired: boolean;
}

export interface MandateBody {
  corporateId: string;
  customerPartyId: string;
  creditorVaId: string;
  mandateReference: string;
  schemeCode: string;
  /** Blank: the customer's primary bank account. */
  debtorIban?: string;
  debtorBic?: string;
  debtorName?: string;
  /** Blank: the bank account's, else the receiving account's. */
  currency?: string;
  mandateType: MandateType;
  validFrom?: string;
  validTo?: string;
  signedDate?: string;
  maxPerCollection?: number;
  frequencyCount?: number;
  frequencyPeriod?: MandatePeriod;
  source: MandateSource;
}

export type CollectionMethod = 'DIRECT_DEBIT' | 'REQUEST_TO_PAY' | 'EXCLUDED';

export interface CollectionLine {
  receivableId: string;
  receivableNumber?: string;
  customerName?: string;
  amount?: number;
  currency?: string;
  dueDate?: string;
  method: CollectionMethod;
  /** Direct debit only. */
  collectionDate?: string;
  mandateId?: string;
  mandateReference?: string;
  schemeCode?: string;
  /** FRST, RCUR or OOFF. */
  sequenceType?: string;
  /** Why it is not a direct debit (or left out). */
  reason?: string;
}

export interface CollectionTotal {
  method: CollectionMethod;
  currency: string;
  count: number;
  amount: number;
}

export interface CollectionPreview {
  lines: CollectionLine[];
  totals: CollectionTotal[];
}

export type LineStatus = 'SENT' | 'SETTLED' | 'AVAILABLE' | 'RTP_SENT' | 'EXCLUDED' | 'FAILED';

export interface RunLine extends Omit<CollectionLine, 'receivableId'> {
  id: string;
  receivableId: string;
  status: LineStatus;
  endToEndId?: string;
  settledOn?: string;
  creditedAmount?: number;
  transactionRef?: string;
  holdUntil?: string;
  releasedOn?: string;
}

export interface RunSummary {
  id: string;
  runRef: string;
  createdAt: string;
  source: 'INVOICES' | 'FILE' | 'API';
  lineStatuses: Partial<Record<LineStatus, number>>;
  totals: CollectionTotal[];
  hasPain008: boolean;
  /** No line is still on its way. */
  completed: boolean;
}

export interface RunDetail {
  run: RunSummary;
  lines: RunLine[];
}

export const collectionsApi = {
  mandates: (corporateId: string) =>
    apiClient.get<ApiResponse<Mandate[]>>('/collections/mandates', { params: { corporateId } }).then(r => r.data.data),

  register: (body: MandateBody) =>
    apiClient.post<ApiResponse<Mandate>>('/collections/mandates', body).then(r => r.data.data),

  changeStatus: (id: string, action: 'suspend' | 'resume' | 'cancel', reason?: string) =>
    apiClient.post<ApiResponse<Mandate>>(`/collections/mandates/${id}/${action}`, { reason }).then(r => r.data.data),

  upload: (corporateId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return apiClient
      .post<ApiResponse<{ imported: number }>>('/collections/mandates/upload', form, {
        params: { corporateId },
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then(r => r.data.data);
  },

  submit: (corporateId: string, receivableIds: string[]) =>
    apiClient.post<ApiResponse<RunDetail>>('/collections/runs', { corporateId, receivableIds }).then(r => r.data.data),

  runs: (corporateId: string) =>
    apiClient.get<ApiResponse<RunSummary[]>>('/collections/runs', { params: { corporateId } }).then(r => r.data.data),

  run: (id: string) => apiClient.get<ApiResponse<RunDetail>>(`/collections/runs/${id}`).then(r => r.data.data),

  /** Saves the run's pain.008 as a file. */
  downloadPain008: async (id: string, runRef: string) => {
    const xml = await apiClient.get<string>(`/collections/runs/${id}/pain008`, { responseType: 'text' }).then(r => r.data);
    const url = URL.createObjectURL(new Blob([xml], { type: 'application/xml' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `${runRef}.pain008.xml`;
    a.click();
    URL.revokeObjectURL(url);
  },

  preview: (corporateId: string, receivableIds: string[]) =>
    apiClient.post<ApiResponse<CollectionPreview>>('/collections/runs/preview', { corporateId, receivableIds }).then(r => r.data.data),
};
