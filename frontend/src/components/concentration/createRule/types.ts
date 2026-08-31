import type React from 'react';

// ============================================================================
// Shared form model for the Create Sweep Rule wizard. The orchestrator
// (CreateRuleModal) owns the state; each step is a presentational component
// that receives `formData` + `setFormData`.
// ============================================================================
export interface SourceAccountItem {
  accountId: string;
  accountNumber: string;
  entityCode: string;
  entityName: string;
}

export interface CreateRuleFormData {
  ruleName: string;
  corporateId: string;
  programId: string;
  sweepType: string;
  targetAccountId: string;
  targetAccountNumber: string;
  targetEntityCode: string;
  targetAmount: string;
  thresholdMin: string;
  thresholdMax: string;
  percentage: string;
  frequency: string;
  currencyCode: string;
  priority: string;
  sourceAccounts: SourceAccountItem[];
}

export type SetCreateRuleFormData = React.Dispatch<React.SetStateAction<CreateRuleFormData>>;
