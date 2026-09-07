import React, { useState, useEffect } from 'react';
import { ChevronRight, CheckCircle } from 'lucide-react';
import { Button } from '../ui';
import { Modal, Stepper } from '../ui/enhanced';
import {
  Corporate,
  Program,
  VirtualAccount,
  corporatesApi,
  programsApi,
  virtualAccountsApi,
} from '../../services/api';
import type { CreateRuleFormData } from './createRule/types';
import type { VirtualizedAccountRow } from '../va/VirtualizedAccountList';
import { Step1Setup } from './createRule/Step1Setup';
import { Step2Accounts } from './createRule/Step2Accounts';
import { Step3Config } from './createRule/Step3Config';
import { Step4Review } from './createRule/Step4Review';

// ============================================================================
// CREATE RULE MODAL — orchestrator
//
// Owns wizard state, data fetching and navigation. Each of the 4 steps is a
// presentational component in ./createRule/. Behaviour is unchanged from the
// previous single-file implementation; this is a structural decomposition.
// ============================================================================
export interface CreateRuleModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (rule: any) => Promise<void>;
}

const INITIAL_FORM: CreateRuleFormData = {
  ruleName: '',
  corporateId: '',
  programId: '',
  sweepType: 'ZERO_BALANCE',
  targetAccountId: '',
  targetAccountNumber: '',
  targetEntityCode: '',
  targetAmount: '',
  thresholdMin: '',
  thresholdMax: '',
  percentage: '',
  frequency: 'DAILY',
  currencyCode: 'AED',
  priority: '1',
  sourceAccounts: [],
};

export const CreateRuleModal: React.FC<CreateRuleModalProps> = ({ isOpen, onClose, onSave }) => {
  const [step, setStep] = useState(1);
  const [saving, setSaving] = useState(false);

  // Corporate & Program state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(false);
  const [loadingPrograms, setLoadingPrograms] = useState(false);
  const [loadingAccounts, setLoadingAccounts] = useState(false);

  const [formData, setFormData] = useState<CreateRuleFormData>({ ...INITIAL_FORM });

  // Fetch corporates on modal open
  useEffect(() => {
    if (isOpen) {
      fetchCorporates();
    }
  }, [isOpen]);

  // Fetch programs when corporate changes
  useEffect(() => {
    if (formData.corporateId) {
      fetchPrograms(formData.corporateId);
      fetchAccounts(formData.corporateId);
    } else {
      setPrograms([]);
      setAccounts([]);
    }
  }, [formData.corporateId]);

  const fetchCorporates = async () => {
    setLoadingCorporates(true);
    try {
      const response = await corporatesApi.getAll(0, 100);
      if (response?.success && response?.data) {
        setCorporates(Array.isArray(response.data) ? response.data : []);
      } else if (Array.isArray(response)) {
        setCorporates(response);
      }
    } catch (err) {
      console.error('Failed to fetch corporates:', err);
    } finally {
      setLoadingCorporates(false);
    }
  };

  const fetchPrograms = async (corporateId: string) => {
    setLoadingPrograms(true);
    try {
      const response = await programsApi.getAll({ corporateId });
      if (response?.success && response?.data) {
        const programData = response.data.content || response.data;
        setPrograms(Array.isArray(programData) ? programData : []);
      } else if (Array.isArray(response)) {
        setPrograms(response);
      }
    } catch (err) {
      console.error('Failed to fetch programs:', err);
    } finally {
      setLoadingPrograms(false);
    }
  };

  const fetchAccounts = async (corporateId: string) => {
    setLoadingAccounts(true);
    try {
      const response = await virtualAccountsApi.getAll(0, 100, corporateId);
      if (response?.success && response?.data) {
        setAccounts(Array.isArray(response.data) ? response.data : []);
      } else if (Array.isArray(response)) {
        setAccounts(response);
      }
    } catch (err) {
      console.error('Failed to fetch accounts:', err);
    } finally {
      setLoadingAccounts(false);
    }
  };

  const resetForm = () => {
    setStep(1);
    setFormData({ ...INITIAL_FORM });
    setPrograms([]);
    setAccounts([]);
  };

  const handleTargetAccountChange = (accountId: string) => {
    const account = accounts.find(a => a.id === accountId);
    if (account) {
      setFormData({
        ...formData,
        targetAccountId: accountId,
        targetAccountNumber: account.vaNumber || account.viban || '',
        targetEntityCode: account.vaName || '',
      });
    }
  };

  const toggleSourceAccount = (account: VirtualAccount) => {
    const exists = formData.sourceAccounts.find(a => a.accountId === account.id);
    if (exists) {
      setFormData({
        ...formData,
        sourceAccounts: formData.sourceAccounts.filter(a => a.accountId !== account.id),
      });
    } else {
      setFormData({
        ...formData,
        sourceAccounts: [
          ...formData.sourceAccounts,
          {
            accountId: account.id,
            accountNumber: account.vaNumber || account.viban || '',
            entityCode: account.vaName || '',
            entityName: account.vaName || '',
          },
        ],
      });
    }
  };

  // Folds a scope/CSV-resolved candidate set into sourceAccounts, matching
  // the SourceAccountItem shape (and the existing manual-toggle convention
  // of using vaName for entityCode/entityName — no distinct entity code is
  // available off a resolved VA summary). Excludes the target account and
  // anything already selected.
  const addSourceAccounts = (resolved: VirtualizedAccountRow[]) => {
    setFormData(prev => {
      const existingIds = new Set(prev.sourceAccounts.map(a => a.accountId));
      const additions = resolved
        .filter(r => r.id !== prev.targetAccountId && !existingIds.has(r.id))
        .map(r => ({
          accountId: r.id,
          accountNumber: r.vaNumber || '',
          entityCode: r.vaName || '',
          entityName: r.vaName || '',
        }));
      return additions.length === 0 ? prev : { ...prev, sourceAccounts: [...prev.sourceAccounts, ...additions] };
    });
  };

  const handleSubmit = async () => {
    setSaving(true);
    try {
      const payload = {
        ruleName: formData.ruleName,
        corporateId: formData.corporateId,
        programId: formData.programId || undefined,
        sweepType: formData.sweepType,
        targetAccountId: formData.targetAccountId,
        targetAccountNumber: formData.targetAccountNumber,
        targetEntityCode: formData.targetEntityCode,
        frequency: formData.frequency,
        currencyCode: formData.currencyCode,
        priority: parseInt(formData.priority),
        sourceAccounts: formData.sourceAccounts,
        ...(formData.sweepType === 'TARGET_BALANCE' && { targetAmount: parseFloat(formData.targetAmount) }),
        ...(formData.sweepType === 'THRESHOLD' && {
          thresholdMin: formData.thresholdMin ? parseFloat(formData.thresholdMin) : undefined,
          thresholdMax: parseFloat(formData.thresholdMax),
        }),
        ...(formData.sweepType === 'PERCENTAGE' && { percentage: parseFloat(formData.percentage) }),
      };
      await onSave(payload);
      onClose();
      resetForm();
    } catch (err) {
      console.error('Failed to create rule:', err);
    } finally {
      setSaving(false);
    }
  };

  const selectedCorporate = corporates.find(c => c.id === formData.corporateId);
  const selectedProgram = programs.find(p => p.id === formData.programId);

  const stepperSteps = [
    { id: 'setup', title: 'Setup' },
    { id: 'accounts', title: 'Accounts' },
    { id: 'config', title: 'Configuration' },
    { id: 'review', title: 'Review' },
  ];

  return (
    <Modal isOpen={isOpen} onClose={() => { onClose(); resetForm(); }} title="Create Sweep Rule" size="lg">
      {/* Step Indicator */}
      <div className="mb-8">
        <Stepper steps={stepperSteps} currentStep={step - 1} size="sm" />
      </div>

      {step === 1 && (
        <Step1Setup
          formData={formData}
          setFormData={setFormData}
          corporates={corporates}
          programs={programs}
          loadingCorporates={loadingCorporates}
          loadingPrograms={loadingPrograms}
        />
      )}

      {step === 2 && (
        <Step2Accounts
          formData={formData}
          accounts={accounts}
          loadingAccounts={loadingAccounts}
          onTargetAccountChange={handleTargetAccountChange}
          onToggleSourceAccount={toggleSourceAccount}
          onAddSourceAccounts={addSourceAccounts}
        />
      )}

      {step === 3 && (
        <Step3Config formData={formData} setFormData={setFormData} />
      )}

      {step === 4 && (
        <Step4Review
          formData={formData}
          selectedCorporate={selectedCorporate}
          selectedProgram={selectedProgram}
        />
      )}

      {/* Navigation */}
      <div className="flex justify-between mt-8 pt-6 border-t border-neutral-200 dark:border-primary-800">
        <Button variant="outline" onClick={() => step > 1 ? setStep(step - 1) : onClose()}>
          {step > 1 ? 'Back' : 'Cancel'}
        </Button>
        <Button
          onClick={() => step < 4 ? setStep(step + 1) : handleSubmit()}
          loading={saving}
          disabled={
            saving ||
            (step === 1 && (!formData.ruleName || !formData.corporateId)) ||
            (step === 2 && (!formData.targetAccountId || formData.sourceAccounts.length === 0))
          }
          rightIcon={step < 4 ? <ChevronRight className="w-4 h-4" /> : <CheckCircle className="w-4 h-4" />}
        >
          {step < 4 ? 'Continue' : 'Create Rule'}
        </Button>
      </div>
    </Modal>
  );
};
