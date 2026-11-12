// Bank statements, read-only (spec docs/specs/bank-statement-intake.md R2a, R11, R16). Backend:
// StatementIntakeController at /api/v1/statements. Statements come in through the inbox or the
// system-to-system intake endpoint only (D7) — no screen posts a statement, so there is no upload here.

import { apiClient, ApiResponse } from './api';

export interface BankStatementFile {
  id: string;
  fileName: string;
  channel: 'INBOX' | 'API';
  receivedAt: string;
  status: 'PROCESSED' | 'FAILED';
  appliedCount: number;
  duplicateCount: number;
  refusedCount: number;
  error?: string;
}

export interface BankStatement {
  id: string;
  fileId: string;
  physicalAccountId?: string;
  iban?: string;
  accountNumber?: string;
  format?: string;
  kind?: 'END_OF_DAY' | 'INTRADAY';
  statementId?: string;
  sequenceNumber?: number;
  /** yyyy-MM-dd */
  statementDate?: string;
  currency?: string;
  closingBooked?: number;
  status: 'APPLIED' | 'DUPLICATE' | 'REFUSED';
  reason?: string;
  gap: boolean;
  breakFlag: boolean;
  /** Informational: closing booked − the platform's balance just before. */
  differenceToPlatform?: number;
  older: boolean;
  cbsFed: boolean;
}

export interface BankStatementLine {
  id: string;
  bankStatementId: string;
  valueDate?: string;
  bookingDate?: string;
  amount: number;
  creditDebit: 'C' | 'D';
  reversal: boolean;
  bankReference?: string;
  endToEndId?: string;
  customerReference?: string;
  remittanceText?: string;
  status: 'FINAL' | 'PROVISIONAL' | 'SUPERSEDED';
}

export interface StatementFileLog {
  file: BankStatementFile;
  statements: BankStatement[];
}

export const statementApi = {
  /** Intake log, newest first (the last `limit` files). */
  files: (limit = 50) =>
    apiClient.get<ApiResponse<StatementFileLog[]>>('/statements/files', { params: { limit } }).then(r => r.data.data),

  /** The account's latest applied statements and their lines. */
  lines: (physicalAccountId: string) =>
    apiClient.get<ApiResponse<{ statements: BankStatement[]; lines: BankStatementLine[] }>>('/statements/lines', { params: { physicalAccountId } })
      .then(r => r.data.data),
};
