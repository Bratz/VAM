// ============================================================================
// CARD PROGRAM TAB
// Corporate card program type, linked card, budget assignment
// ============================================================================

import React from 'react';
import { CreditCard, Users, Building2, Briefcase, Info } from 'lucide-react';
import { Input, Badge } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { FormField, SelectField, FormSection, FormRow } from './FormComponents';
import { CreateVaRequest, Program, ProgramTypeConfig } from '../vaTypes';
import { cn } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface CardProgramTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  config?: ProgramTypeConfig;
}

// ============================================================================
// OPTIONS
// ============================================================================

const CARD_PROGRAM_TYPES = [
  { 
    value: 'TRAVEL', 
    label: 'Travel & Entertainment', 
    icon: '✈️',
    description: 'Business travel expenses, hotels, airlines',
    mccs: ['3000-3299', '3351-3441', '3501-3790', '4511', '7011'],
  },
  { 
    value: 'PROCUREMENT', 
    label: 'Procurement', 
    icon: '📦',
    description: 'Vendor payments, supplies, equipment',
    mccs: ['5200', '5311', '5732', '5999'],
  },
  { 
    value: 'FLEET', 
    label: 'Fleet/Fuel', 
    icon: '⛽',
    description: 'Fuel purchases, vehicle maintenance',
    mccs: ['5541', '5542', '7538', '7542'],
  },
  { 
    value: 'VIRTUAL', 
    label: 'Virtual Card', 
    icon: '💳',
    description: 'Single-use or recurring virtual cards',
    mccs: [],
  },
  { 
    value: 'EXPENSE', 
    label: 'Employee Expense', 
    icon: '👤',
    description: 'General employee expenses',
    mccs: [],
  },
  { 
    value: 'PETTY_CASH', 
    label: 'Petty Cash', 
    icon: '💵',
    description: 'Small, miscellaneous expenses',
    mccs: [],
  },
];

// ============================================================================
// COMPONENT
// ============================================================================

export const CardProgramTab: React.FC<CardProgramTabProps> = ({
  formData,
  setFormData,
  errors,
  program,
  config,
}) => {
  // Update field helper
  const updateField = <K extends keyof CreateVaRequest>(
    field: K,
    value: CreateVaRequest[K]
  ) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  // Get selected card program type info
  const selectedType = CARD_PROGRAM_TYPES.find(t => t.value === formData.cardProgramType);

  return (
    <div className="space-y-6">
      {/* Card Program Type Section */}
      <FormSection
        title="Card Program Type"
        description="Select the type of corporate card program"
        icon={<CreditCard className="w-5 h-5 text-primary-600" />}
      >
        <FormField 
          label="Program Type"
          hint="Determines default MCC restrictions and expense categories"
        >
          <SelectField
            value={formData.cardProgramType || ''}
            onChange={(v) => updateField('cardProgramType', v)}
            options={CARD_PROGRAM_TYPES.map(t => ({ 
              value: t.value, 
              label: `${t.icon} ${t.label}` 
            }))}
            placeholder="Select card program type..."
          />
        </FormField>

        {/* Card Program Type Cards */}
        <div className="mt-4 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {CARD_PROGRAM_TYPES.map((type) => (
            <button
              key={type.value}
              type="button"
              onClick={() => updateField('cardProgramType', type.value)}
              className={cn(
                'p-4 rounded-lg border text-left transition-all',
                formData.cardProgramType === type.value
                  ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-200'
                  : 'border-neutral-200 hover:border-primary-300 hover:bg-neutral-50'
              )}
            >
              <div className="flex items-center gap-2 mb-2">
                <span className="text-xl">{type.icon}</span>
                <span className="font-medium text-sm">{type.label}</span>
              </div>
              <p className="text-xs text-neutral-500">{type.description}</p>
              {type.mccs.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1">
                  {type.mccs.slice(0, 3).map(mcc => (
                    <Badge key={mcc} variant="neutral" size="sm">{mcc}</Badge>
                  ))}
                  {type.mccs.length > 3 && (
                    <Badge variant="neutral" size="sm">+{type.mccs.length - 3}</Badge>
                  )}
                </div>
              )}
            </button>
          ))}
        </div>

        {/* Selected Type Info */}
        {selectedType && (
          <Alert variant="info" className="mt-4">
            <Info className="w-4 h-4" />
            <div>
              <strong>{selectedType.label}:</strong> {selectedType.description}
              {selectedType.mccs.length > 0 && (
                <p className="text-sm mt-1">
                  Typical MCCs: {selectedType.mccs.join(', ')}
                </p>
              )}
            </div>
          </Alert>
        )}
      </FormSection>

      {/* Card Linking Section */}
      <FormSection
        title="Card Linking"
        description="Link this account to a physical or virtual card"
        icon={<CreditCard className="w-5 h-5 text-primary-600" />}
      >
        <FormRow cols={2}>
          <FormField 
            label="Linked Card ID" 
            hint="Physical card number or virtual card token"
          >
            <Input
              value={formData.linkedCardId || ''}
              onChange={(e) => updateField('linkedCardId', e.target.value)}
              placeholder="Card UUID, PAN token, or masked PAN"
            />
          </FormField>

          <FormField 
            label="Card Status"
            hint="Current status of the linked card (read-only)"
          >
            <div className="flex items-center gap-2 px-3 py-2 bg-neutral-50 border border-neutral-200 rounded-lg">
              {formData.linkedCardId ? (
                <>
                  <span className="w-2 h-2 rounded-full bg-success-500" />
                  <span className="text-sm text-neutral-600">Card linked</span>
                </>
              ) : (
                <>
                  <span className="w-2 h-2 rounded-full bg-neutral-300" />
                  <span className="text-sm text-neutral-500">No card linked</span>
                </>
              )}
            </div>
          </FormField>
        </FormRow>
      </FormSection>

      {/* Budget Assignment Section */}
      <FormSection
        title="Budget Assignment"
        description="Assign budget ownership and cost allocation"
        icon={<Briefcase className="w-5 h-5 text-primary-600" />}
      >
        <FormRow cols={2}>
          <FormField 
            label="Budget Owner ID" 
            hint="Person responsible for this budget"
          >
            <Input
              value={formData.budgetOwnerId || ''}
              onChange={(e) => updateField('budgetOwnerId', e.target.value)}
              placeholder="Employee ID or UUID"
            />
          </FormField>

          <FormField 
            label="Cost Center"
            hint="Cost center for expense allocation"
          >
            <Input
              value={formData.costCenter || ''}
              onChange={(e) => updateField('costCenter', e.target.value)}
              placeholder="e.g., CC-SALES-001"
            />
          </FormField>
        </FormRow>

        <FormRow cols={2} className="mt-4">
          <FormField 
            label="Department"
            hint="Department for reporting"
          >
            <Input
              value={formData.department || ''}
              onChange={(e) => updateField('department', e.target.value)}
              placeholder="e.g., Marketing, Engineering, Sales"
            />
          </FormField>

          <FormField 
            label="Project Code"
            hint="Optional project code for allocation"
          >
            <Input
              value="" // Not in current schema, placeholder
              onChange={() => {}}
              placeholder="e.g., PRJ-2024-001"
              disabled
            />
          </FormField>
        </FormRow>
      </FormSection>

      {/* Budget Owner Quick Select */}
      <FormSection
        title="Quick Assignment"
        description="Common budget owner assignments"
        icon={<Users className="w-5 h-5 text-primary-600" />}
      >
        <div className="flex flex-wrap gap-2">
          <QuickAssignButton
            label="Self"
            onClick={() => {
              updateField('budgetOwnerId', 'current-user');
              updateField('department', 'My Department');
            }}
            selected={formData.budgetOwnerId === 'current-user'}
          />
          <QuickAssignButton
            label="Marketing"
            onClick={() => {
              updateField('costCenter', 'CC-MKT-001');
              updateField('department', 'Marketing');
            }}
            selected={formData.department === 'Marketing'}
          />
          <QuickAssignButton
            label="Sales"
            onClick={() => {
              updateField('costCenter', 'CC-SALES-001');
              updateField('department', 'Sales');
            }}
            selected={formData.department === 'Sales'}
          />
          <QuickAssignButton
            label="Engineering"
            onClick={() => {
              updateField('costCenter', 'CC-ENG-001');
              updateField('department', 'Engineering');
            }}
            selected={formData.department === 'Engineering'}
          />
          <QuickAssignButton
            label="Operations"
            onClick={() => {
              updateField('costCenter', 'CC-OPS-001');
              updateField('department', 'Operations');
            }}
            selected={formData.department === 'Operations'}
          />
          <QuickAssignButton
            label="Finance"
            onClick={() => {
              updateField('costCenter', 'CC-FIN-001');
              updateField('department', 'Finance');
            }}
            selected={formData.department === 'Finance'}
          />
        </div>
      </FormSection>

      {/* Summary */}
      {(formData.cardProgramType || formData.linkedCardId || formData.budgetOwnerId) && (
        <Alert variant="success">
          <CreditCard className="w-4 h-4" />
          <div>
            <strong>Card Program Configuration:</strong>
            <ul className="text-sm mt-1 list-disc list-inside">
              {formData.cardProgramType && (
                <li>Type: {CARD_PROGRAM_TYPES.find(t => t.value === formData.cardProgramType)?.label}</li>
              )}
              {formData.linkedCardId && (
                <li>Card linked: {formData.linkedCardId.substring(0, 8)}...</li>
              )}
              {formData.budgetOwnerId && (
                <li>Budget owner assigned</li>
              )}
              {formData.costCenter && (
                <li>Cost center: {formData.costCenter}</li>
              )}
              {formData.department && (
                <li>Department: {formData.department}</li>
              )}
            </ul>
          </div>
        </Alert>
      )}
    </div>
  );
};

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

interface QuickAssignButtonProps {
  label: string;
  onClick: () => void;
  selected?: boolean;
}

const QuickAssignButton: React.FC<QuickAssignButtonProps> = ({
  label,
  onClick,
  selected,
}) => (
  <button
    type="button"
    onClick={onClick}
    className={cn(
      'px-4 py-2 rounded-lg border text-sm font-medium transition-colors',
      selected
        ? 'border-primary-500 bg-primary-100 text-primary-700'
        : 'border-neutral-200 hover:border-primary-300 text-neutral-600 hover:bg-neutral-50'
    )}
  >
    {label}
  </button>
);

export default CardProgramTab;