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

  preview: (corporateId: string, receivableIds: string[]) =>
    apiClient.post<ApiResponse<CollectionPreview>>('/collections/runs/preview', { corporateId, receivableIds }).then(r => r.data.data),
};
