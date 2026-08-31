import React from 'react';
import { TrendingUp, Layers } from 'lucide-react';
import { Card, Input, Select, StatusIconBadge } from '../../ui';
import type { CreateRuleFormData, SetCreateRuleFormData } from './types';

// ============================================================================
// Step 3 — Configuration: currency, priority, and sweep-type-specific fields
// ============================================================================
export interface Step3ConfigProps {
  formData: CreateRuleFormData;
  setFormData: SetCreateRuleFormData;
}

export const Step3Config: React.FC<Step3ConfigProps> = ({ formData, setFormData }) => (
  <div className="space-y-6 animate-fade-in">
    <div className="grid grid-cols-2 gap-6">
      <Select
        label="Currency"
        value={formData.currencyCode}
        onChange={(e) => setFormData({ ...formData, currencyCode: e.target.value })}
        options={[
          { value: 'AED', label: 'AED - UAE Dirham' },
          { value: 'USD', label: 'USD - US Dollar' },
          { value: 'EUR', label: 'EUR - Euro' },
          { value: 'GBP', label: 'GBP - British Pound' },
        ]}
      />
      <Input
        label="Priority"
        type="number"
        min={1}
        max={10}
        value={formData.priority}
        onChange={(e) => setFormData({ ...formData, priority: e.target.value })}
        hint="1 = Highest priority"
      />
    </div>

    {/* Type-specific fields */}
    {formData.sweepType === 'TARGET_BALANCE' && (
      <Card className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700">
        <div className="flex items-start gap-4">
          <StatusIconBadge tone="primary" icon={TrendingUp} className="flex-shrink-0" />
          <div className="flex-1">
            <Input
              label="Target Balance Amount"
              type="number"
              placeholder="e.g., 500000"
              value={formData.targetAmount}
              onChange={(e) => setFormData({ ...formData, targetAmount: e.target.value })}
              hint="Funds will be swept to maintain this balance in source accounts"
            />
          </div>
        </div>
      </Card>
    )}

    {formData.sweepType === 'THRESHOLD' && (
      <Card className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30">
        <div className="flex items-start gap-4">
          <StatusIconBadge tone="warning" icon={Layers} className="flex-shrink-0" />
          <div className="flex-1 grid grid-cols-2 gap-4">
            <Input
              label="Minimum Threshold"
              type="number"
              placeholder="e.g., 100000"
              value={formData.thresholdMin}
              onChange={(e) => setFormData({ ...formData, thresholdMin: e.target.value })}
              hint="Leave at minimum balance"
            />
            <Input
              label="Maximum Threshold"
              type="number"
              placeholder="e.g., 1000000"
              value={formData.thresholdMax}
              onChange={(e) => setFormData({ ...formData, thresholdMax: e.target.value })}
              hint="Sweep when exceeded"
            />
          </div>
        </div>
      </Card>
    )}

    {formData.sweepType === 'PERCENTAGE' && (
      <Card className="bg-info-50/50 dark:bg-info-500/10 border-info-200/60 dark:border-info-500/30">
        <div className="flex items-start gap-4">
          <StatusIconBadge tone="info" icon={TrendingUp} className="flex-shrink-0" />
          <div className="flex-1">
            <Input
              label="Percentage to Sweep"
              type="number"
              min={1}
              max={100}
              placeholder="e.g., 25"
              value={formData.percentage}
              onChange={(e) => setFormData({ ...formData, percentage: e.target.value })}
              hint="Sweep this percentage of available balance"
            />
          </div>
        </div>
      </Card>
    )}
  </div>
);
