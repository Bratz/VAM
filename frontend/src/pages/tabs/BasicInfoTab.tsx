// ============================================================================
// BASIC INFO TAB
// Core virtual account information: name, corporate, physical account, currency
// ============================================================================

import React from 'react';
import { Info } from 'lucide-react';
import { Input, Toggle } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { FormField, SelectField } from './FormComponents';
import { CreateVaRequest, Program } from '../vaTypes';

// ============================================================================
// TYPES
// ============================================================================

export interface BasicInfoTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  corporates: { id: string; name: string }[];
  physicalAccounts: { id: string; accountNumber: string; currency: string }[];
}

// ============================================================================
// COMPONENT
// ============================================================================

export const BasicInfoTab: React.FC<BasicInfoTabProps> = ({
  formData,
  setFormData,
  errors,
  program,
  corporates,
  physicalAccounts,
}) => {
  // Update field helper
  const updateField = <K extends keyof CreateVaRequest>(
    field: K,
    value: CreateVaRequest[K]
  ) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  // Filter physical accounts by currency if program is selected
  const filteredPhysicalAccounts = program
    ? physicalAccounts.filter(a => a.currency === program.currencyCode)
    : physicalAccounts;

  return (
    <div className="space-y-6">
      {/* Inherit Program Defaults Toggle */}
      {program && (
        <div className="flex items-center justify-between p-4 bg-primary-50 dark:bg-primary-800/40 rounded-lg border border-primary-100">
          <div>
            <h4 className="font-medium text-primary-900 dark:text-neutral-50">Inherit Program Defaults</h4>
            <p className="text-body-sm text-primary-700 dark:text-neutral-200">
              Apply default limits, KYC requirements, and wallet settings from the program
            </p>
          </div>
          <Toggle
            aria-label="Inherit program defaults"
            checked={formData.inheritProgramDefaults ?? true}
            onChange={(checked) => updateField('inheritProgramDefaults', checked)}
          />
        </div>
      )}

      {/* Program Info Banner */}
      {program && (
        <Alert variant="info">
          <Info className="w-4 h-4" />
          <div>
            <strong>Program:</strong> {program.programName} ({program.programCode})
            <span className="mx-2">•</span>
            <span className="mx-2">•</span>
            <strong>Currency:</strong> {program.currencyCode}
          </div>
        </Alert>
      )}

      {/* Core Fields - Row 1 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <FormField 
          label="Account Name" 
          required 
          error={errors.vaName}
          hint="A descriptive name for this virtual account"
        >
          <Input
            value={formData.vaName}
            onChange={(e) => updateField('vaName', e.target.value)}
            placeholder="Enter account name"
            error={!!errors.vaName}
            maxLength={100}
          />
        </FormField>

        <FormField 
          label="VA Number Prefix" 
          hint="Optional. Uses program prefix if not specified."
        >
          <Input
            value={formData.vaPrefix || ''}
            onChange={(e) => updateField('vaPrefix', e.target.value.toUpperCase())}
            placeholder="e.g., COL, WAL, IHB"
            maxLength={10}
          />
        </FormField>
      </div>

      {/* Core Fields - Row 2 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <FormField 
          label="Corporate" 
          required 
          error={errors.corporateId}
          hint="The corporate entity that owns this account"
        >
          <SelectField
            value={formData.corporateId}
            onChange={(v) => updateField('corporateId', v)}
            options={corporates.map(c => ({ value: c.id, label: c.name }))}
            placeholder="Select corporate..."
            error={!!errors.corporateId}
          />
        </FormField>

        <FormField 
          label="Physical Account" 
          required 
          error={errors.physicalAccountId}
          hint="The underlying physical bank account"
        >
          <SelectField
            value={formData.physicalAccountId}
            onChange={(v) => updateField('physicalAccountId', v)}
            options={filteredPhysicalAccounts.map(a => ({ 
              value: a.id, 
              label: `${a.accountNumber} (${a.currency})` 
            }))}
            placeholder="Select physical account..."
            error={!!errors.physicalAccountId}
          />
          {program && filteredPhysicalAccounts.length === 0 && (
            <p className="caption-warning mt-1">
              No physical accounts found for currency {program.currencyCode}
            </p>
          )}
        </FormField>
      </div>

      {/* Core Fields - Row 3 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <FormField 
          label="Currency" 
          required 
          error={errors.currencyCode}
          hint="ISO 4217 currency code"
        >
          <Input
            value={formData.currencyCode}
            onChange={(e) => updateField('currencyCode', e.target.value.toUpperCase())}
            placeholder="e.g., USD, EUR, GBP"
            maxLength={3}
            disabled={!!program}
            error={!!errors.currencyCode}
          />
          {program && (
            <p className="caption mt-1">
              Currency is set by the program and cannot be changed
            </p>
          )}
        </FormField>

        <FormField 
          label="VIBAN" 
          hint="Virtual IBAN for incoming payments (optional)"
        >
          <Input
            value={formData.viban || ''}
            onChange={(e) => updateField('viban', e.target.value.toUpperCase())}
            placeholder="e.g., GB82WEST12345698765432"
            maxLength={34}
          />
        </FormField>
      </div>

      {/* External Reference & Metadata */}
      <div className="border-t border-neutral-200 pt-6">
        <h4 className="body-strong mb-4">Additional Information</h4>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="External Reference" 
            hint="Your internal reference ID (e.g., ERP system ID)"
          >
            <Input
              value={formData.externalReference || ''}
              onChange={(e) => updateField('externalReference', e.target.value)}
              placeholder="e.g., ERP-12345, CRM-67890"
              maxLength={100}
            />
          </FormField>

          <FormField 
            label="Metadata" 
            hint="JSON metadata for custom fields"
          >
            <Input
              value={formData.metadata || ''}
              onChange={(e) => updateField('metadata', e.target.value)}
              placeholder='e.g., {"department": "sales", "region": "EMEA"}'
            />
          </FormField>
        </div>
      </div>

      {/* Validation Summary */}
      {Object.keys(errors).length > 0 && (
        <Alert variant="danger">
          <div>
            <strong>Please fix the following errors:</strong>
            <ul className="list-disc list-inside mt-1 text-body-sm">
              {Object.entries(errors).map(([field, message]) => (
                <li key={field}>{message}</li>
              ))}
            </ul>
          </div>
        </Alert>
      )}
    </div>
  );
};

export default BasicInfoTab;