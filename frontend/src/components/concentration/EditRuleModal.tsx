import React, { useState, useEffect } from 'react';
import { Layers, CheckCircle } from 'lucide-react';
import { Card, Button, Input, Select, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { SweepRule } from '../../services/api';
import { SWEEP_TYPES, FREQUENCIES } from './constants';

// ============================================================================
// EDIT RULE MODAL
// ============================================================================
export interface EditRuleModalProps {
  isOpen: boolean;
  onClose: () => void;
  rule: SweepRule | null;
  onSave: (id: string, data: any) => Promise<void>;
}

export const EditRuleModal: React.FC<EditRuleModalProps> = ({ isOpen, onClose, rule, onSave }) => {
  const [saving, setSaving] = useState(false);
  const [formData, setFormData] = useState({
    ruleName: '',
    sweepType: 'ZERO_BALANCE',
    targetAccountNumber: '',
    targetEntityCode: '',
    targetAmount: '',
    thresholdMin: '',
    thresholdMax: '',
    percentage: '',
    frequency: 'DAILY',
    currencyCode: 'AED',
    priority: '1',
  });

  useEffect(() => {
    if (rule) {
      setFormData({
        ruleName: rule.ruleName || '',
        sweepType: rule.sweepType || 'ZERO_BALANCE',
        targetAccountNumber: (rule as any).targetAccountNumber || '',
        targetEntityCode: (rule as any).targetEntityCode || '',
        targetAmount: (rule as any).targetAmount?.toString() || '',
        thresholdMin: (rule as any).thresholdMin?.toString() || '',
        thresholdMax: (rule as any).thresholdMax?.toString() || '',
        percentage: (rule as any).percentage?.toString() || '',
        frequency: rule.frequency || 'DAILY',
        currencyCode: (rule as any).currencyCode || 'AED',
        priority: ((rule as any).priority || 1).toString(),
      });
    }
  }, [rule]);

  const handleSubmit = async () => {
    if (!rule) return;
    setSaving(true);
    try {
      const payload = {
        ruleName: formData.ruleName,
        sweepType: formData.sweepType,
        frequency: formData.frequency,
        currencyCode: formData.currencyCode,
        priority: parseInt(formData.priority),
        targetAccountNumber: formData.targetAccountNumber,
        targetEntityCode: formData.targetEntityCode,
        ...(formData.sweepType === 'TARGET_BALANCE' && { targetAmount: parseFloat(formData.targetAmount) }),
        ...(formData.sweepType === 'THRESHOLD' && {
          thresholdMin: formData.thresholdMin ? parseFloat(formData.thresholdMin) : undefined,
          thresholdMax: parseFloat(formData.thresholdMax),
        }),
        ...(formData.sweepType === 'PERCENTAGE' && { percentage: parseFloat(formData.percentage) }),
      };
      await onSave(rule.id, payload);
      onClose();
    } catch (err) {
      console.error('Failed to update rule:', err);
    } finally {
      setSaving(false);
    }
  };

  if (!rule) return null;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Edit Sweep Rule" size="md">
      <div className="space-y-6">
        {/* Rule Header Info */}
        <Card className="bg-neutral-50/50 dark:bg-primary-950/50 border-neutral-200/60 dark:border-primary-800" padding="sm">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="primary" icon={Layers} />
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Editing Rule</p>
              <p className="font-mono text-sm text-neutral-600 dark:text-neutral-300">{rule.ruleReference}</p>
            </div>
          </div>
        </Card>

        <Input
          label="Rule Name"
          value={formData.ruleName}
          onChange={(e) => setFormData({ ...formData, ruleName: e.target.value })}
        />

        <Select
          label="Sweep Type"
          value={formData.sweepType}
          onChange={(e) => setFormData({ ...formData, sweepType: e.target.value })}
          options={(Object.keys(SWEEP_TYPES) as Array<keyof typeof SWEEP_TYPES>).map((type) => ({
            value: type,
            label: SWEEP_TYPES[type].label
          }))}
        />

        <div className="grid grid-cols-2 gap-6">
          <Select
            label="Frequency"
            value={formData.frequency}
            onChange={(e) => setFormData({ ...formData, frequency: e.target.value })}
            options={FREQUENCIES.map((f) => ({ value: f.value, label: f.label }))}
          />
          <Input
            label="Priority"
            type="number"
            min={1}
            max={10}
            value={formData.priority}
            onChange={(e) => setFormData({ ...formData, priority: e.target.value })}
            hint="1 = Highest"
          />
        </div>

        {formData.sweepType === 'TARGET_BALANCE' && (
          <Card className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700">
            <Input
              label="Target Balance Amount"
              type="number"
              value={formData.targetAmount}
              onChange={(e) => setFormData({ ...formData, targetAmount: e.target.value })}
              hint="Maintain this balance in source accounts"
            />
          </Card>
        )}

        {formData.sweepType === 'THRESHOLD' && (
          <Card className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30">
            <div className="grid grid-cols-2 gap-4">
              <Input
                label="Min Threshold"
                type="number"
                value={formData.thresholdMin}
                onChange={(e) => setFormData({ ...formData, thresholdMin: e.target.value })}
              />
              <Input
                label="Max Threshold"
                type="number"
                value={formData.thresholdMax}
                onChange={(e) => setFormData({ ...formData, thresholdMax: e.target.value })}
              />
            </div>
          </Card>
        )}

        {formData.sweepType === 'PERCENTAGE' && (
          <Card className="bg-info-50/50 dark:bg-info-500/10 border-info-200/60 dark:border-info-500/30">
            <Input
              label="Percentage to Sweep"
              type="number"
              min={1}
              max={100}
              value={formData.percentage}
              onChange={(e) => setFormData({ ...formData, percentage: e.target.value })}
              hint="Percentage of available balance"
            />
          </Card>
        )}
      </div>

      <div className="flex justify-end gap-3 mt-8 pt-6 border-t border-neutral-200 dark:border-primary-800">
        <Button variant="outline" onClick={onClose}>Cancel</Button>
        <Button
          onClick={handleSubmit}
          loading={saving}
          disabled={saving || !formData.ruleName}
          leftIcon={<CheckCircle className="w-4 h-4" />}
        >
          Save Changes
        </Button>
      </div>
    </Modal>
  );
};
