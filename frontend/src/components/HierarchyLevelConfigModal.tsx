// ============================================================================
// HIERARCHY LEVEL CONFIGURATION MODAL
// Path: src/components/HierarchyLevelConfigModal.tsx
// Purpose: Allow corporate users to configure hierarchy levels and allowed values
// ============================================================================

import React, { useState, useEffect } from 'react';
import {
  Settings, Save, X, Plus, Trash2, GripVertical, ChevronDown, ChevronUp,
  AlertCircle, CheckCircle, Info, Layers, Tag, List, Edit2, RefreshCw,
} from 'lucide-react';
import { Modal } from './ui/enhanced';
import { Card, Badge, Button, Input, Select } from './ui';
import { cn } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface HierarchyLevelConfig {
  id?: string;
  programId?: string;
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
  description?: string;
}

interface HierarchyLevelConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
  programId: string;
  programName?: string;
  programType?: string;
  initialLevels?: HierarchyLevelConfig[];
  onSave: (levels: HierarchyLevelConfig[]) => Promise<void>;
  /** If true, shows a warning that modifying levels may affect existing hierarchy nodes */
  hasExistingNodes?: boolean;
  /** Number of existing hierarchy nodes (for display in warning) */
  existingNodeCount?: number;
  /** Program's hierarchy level cap (Program.maxHierarchyDepth). The editor
   *  honors this — hierarchy depth is extensible by design, not fixed at 7. */
  maxDepth?: number;
}

/** Backend default for Program.maxHierarchyDepth. */
const DEFAULT_MAX_DEPTH = 20;

// ============================================================================
// DIMENSION TYPES - Available options for each level
// ============================================================================

const DIMENSION_TYPES = [
  { value: 'CURRENCY', label: 'Currency', description: 'Currency code (AED, USD, EUR)' },
  { value: 'REGION', label: 'Region', description: 'Geographic region' },
  { value: 'COUNTRY', label: 'Country', description: 'Country code' },
  { value: 'STATE', label: 'State/Province', description: 'State or province' },
  { value: 'CITY', label: 'City', description: 'City name' },
  { value: 'ENTITY', label: 'Legal Entity', description: 'Legal entity code' },
  { value: 'DEPARTMENT', label: 'Department', description: 'Department or division' },
  { value: 'COST_CENTER', label: 'Cost Center', description: 'Cost center code' },
  { value: 'ACCOUNT_TYPE', label: 'Account Type', description: 'Account classification' },
  { value: 'CHANNEL', label: 'Channel', description: 'Collection/payment channel' },
  { value: 'PLATFORM', label: 'Platform', description: 'Platform or gateway' },
  { value: 'SEGMENT', label: 'Segment', description: 'Customer segment' },
  { value: 'CUSTOMER', label: 'Customer', description: 'Customer identifier' },
  { value: 'MERCHANT', label: 'Merchant', description: 'Merchant identifier' },
  { value: 'PARTNER', label: 'Partner', description: 'Partner identifier' },
  { value: 'TIER', label: 'Tier', description: 'Customer tier level' },
  { value: 'BUDGET_OWNER', label: 'Budget Owner', description: 'Budget owner' },
  { value: 'VIRTUAL_ACCOUNT', label: 'Virtual Account', description: 'Leaf level VA' },
];

// Common allowed values for different dimension types
const SUGGESTED_VALUES: Record<string, string[]> = {
  CURRENCY: ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'INR', 'CNY', 'JPY'],
  REGION: ['NORTH', 'SOUTH', 'EAST', 'WEST', 'MENA', 'APAC', 'EMEA', 'AMERICAS'],
  COUNTRY: ['AE', 'SA', 'US', 'UK', 'IN', 'SG', 'DE', 'FR'],
  SEGMENT: ['CORPORATE', 'SME', 'RETAIL', 'GOVERNMENT', 'ENTERPRISE'],
  CHANNEL: ['INVOICE', 'ECOMMERCE', 'POS', 'DIRECT', 'STANDING_ORDER', 'QR_CODE'],
  ACCOUNT_TYPE: ['PAYABLES', 'RECEIVABLES', 'TAXES', 'PAYROLL', 'CAPEX', 'OPEX', 'INTERCOMPANY'],
  TIER: ['PLATINUM', 'GOLD', 'SILVER', 'BRONZE', 'STANDARD'],
};

// ============================================================================
// LEVEL EDITOR COMPONENT
// ============================================================================

interface LevelEditorProps {
  level: HierarchyLevelConfig;
  onChange: (level: HierarchyLevelConfig) => void;
  isExpanded: boolean;
  onToggleExpand: () => void;
  canDelete: boolean;
  onDelete: () => void;
}

const LevelEditor: React.FC<LevelEditorProps> = ({
  level,
  onChange,
  isExpanded,
  onToggleExpand,
  canDelete,
  onDelete,
}) => {
  const [newValue, setNewValue] = useState('');

  const handleAddValue = () => {
    if (!newValue.trim()) return;
    const currentValues = level.allowedValues || [];
    if (!currentValues.includes(newValue.trim().toUpperCase())) {
      onChange({
        ...level,
        allowedValues: [...currentValues, newValue.trim().toUpperCase()],
      });
    }
    setNewValue('');
  };

  const handleRemoveValue = (value: string) => {
    onChange({
      ...level,
      allowedValues: (level.allowedValues || []).filter(v => v !== value),
    });
  };

  const handleApplySuggested = () => {
    const suggested = SUGGESTED_VALUES[level.dimensionType];
    if (suggested) {
      onChange({ ...level, allowedValues: suggested });
    }
  };

  const dimensionInfo = DIMENSION_TYPES.find(d => d.value === level.dimensionType);
  const hasSuggestedValues = SUGGESTED_VALUES[level.dimensionType];

  return (
    <div className={cn(
      'border rounded-lg transition-all',
      isExpanded ? 'border-primary-300 bg-primary-50/50' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800'
    )}>
      {/* Header */}
      <div
        className="flex items-center gap-3 p-3 cursor-pointer"
        onClick={onToggleExpand}
      >
        <div className="flex items-center gap-2 flex-shrink-0">
          <GripVertical className="w-4 h-4 text-neutral-400" />
          <span className="w-7 h-7 rounded-full bg-primary-100 text-primary-700 flex items-center justify-center text-sm font-semibold dark:bg-primary-700 dark:text-neutral-200">
            {level.levelNumber}
          </span>
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-primary-900 dark:text-neutral-50">{level.levelName}</span>
            <Badge variant="neutral" size="sm">{level.dimensionType}</Badge>
            {level.isRequired && <Badge variant="success" size="sm">Required</Badge>}
            {(level.allowedValues?.length || 0) > 0 && (
              <Badge variant="info" size="sm">{level.allowedValues?.length} values</Badge>
            )}
          </div>
          {level.description && (
            <p className="text-xs text-neutral-500 mt-0.5 truncate dark:text-neutral-400">{level.description}</p>
          )}
        </div>

        <div className="flex items-center gap-2">
          {canDelete && (
            <button
              onClick={(e) => { e.stopPropagation(); onDelete(); }}
              className="p-1 text-neutral-400 hover:text-error-600 transition-colors"
              title="Remove level"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          )}
          {isExpanded ? <ChevronUp className="w-4 h-4 text-neutral-400" /> : <ChevronDown className="w-4 h-4 text-neutral-400" />}
        </div>
      </div>

      {/* Expanded Content */}
      {isExpanded && (
        <div className="px-4 pb-4 pt-2 border-t border-neutral-200 space-y-4 dark:border-primary-800">
          {/* Level Name & Dimension Type */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <Input
                label="Level Name"
                inputSize="sm"
                value={level.levelName}
                onChange={(e) => onChange({ ...level, levelName: e.target.value })}
                placeholder="e.g., Region, Department"
              />
            </div>
            <div>
              <Select
                label="Dimension Type"
                selectSize="sm"
                value={level.dimensionType}
                onChange={(e) => onChange({ ...level, dimensionType: e.target.value, allowedValues: [] })}
                options={DIMENSION_TYPES.map((d) => ({
                  value: d.value,
                  label: d.label,
                }))}
              />
            </div>
          </div>

          {/* Description */}
          <div>
            <Input
              label="Description"
              inputSize="sm"
              value={level.description || ''}
              onChange={(e) => onChange({ ...level, description: e.target.value })}
              placeholder="What this level represents..."
            />
          </div>

          {/* Required Toggle */}
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              className="w-4 h-4 rounded border-neutral-300 dark:border-primary-700"
              checked={level.isRequired || false}
              onChange={(e) => onChange({ ...level, isRequired: e.target.checked })}
            />
            <span className="text-sm text-neutral-700 dark:text-neutral-200">This level is required</span>
          </label>

          {/* Allowed Values */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="block text-xs font-medium text-neutral-600 dark:text-neutral-300">
                Allowed Values
                <span className="font-normal text-neutral-400 ml-1">(leave empty for any value)</span>
              </label>
              {hasSuggestedValues && (
                <button
                  onClick={handleApplySuggested}
                  className="text-xs text-primary-600 hover:text-primary-700 flex items-center gap-1 dark:text-primary-200"
                >
                  <RefreshCw className="w-3 h-3" />
                  Apply suggested
                </button>
              )}
            </div>

            {/* Current Values */}
            <div className="flex flex-wrap gap-2 mb-2 min-h-[32px]">
              {(level.allowedValues || []).map((value) => (
                <span
                  key={value}
                  className="inline-flex items-center gap-1 px-2 py-1 bg-white border border-neutral-200 rounded text-sm dark:bg-primary-900 dark:border-primary-800"
                >
                  {value}
                  <button
                    onClick={() => handleRemoveValue(value)}
                    className="text-neutral-400 hover:text-error-600"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </span>
              ))}
              {(level.allowedValues?.length || 0) === 0 && (
                <span className="text-xs text-neutral-400 italic">No restrictions - any value allowed</span>
              )}
            </div>

            {/* Add New Value */}
            <div className="flex gap-2">
              <input
                type="text"
                className="flex-1 px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                placeholder="Add allowed value..."
                value={newValue}
                onChange={(e) => setNewValue(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAddValue()}
              />
              <Button size="sm" variant="outline" onClick={handleAddValue}>
                <Plus className="w-4 h-4" />
              </Button>
            </div>

            {/* Suggested Values Quick Add */}
            {hasSuggestedValues && (
              <div className="mt-2">
                <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Quick add:</p>
                <div className="flex flex-wrap gap-1">
                  {SUGGESTED_VALUES[level.dimensionType]
                    .filter(v => !(level.allowedValues || []).includes(v))
                    .slice(0, 6)
                    .map(value => (
                      <button
                        key={value}
                        onClick={() => onChange({
                          ...level,
                          allowedValues: [...(level.allowedValues || []), value],
                        })}
                        className="px-2 py-0.5 text-xs bg-neutral-100 hover:bg-primary-100 rounded transition-colors dark:bg-primary-800"
                      >
                        + {value}
                      </button>
                    ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MAIN MODAL COMPONENT
// ============================================================================

export const HierarchyLevelConfigModal: React.FC<HierarchyLevelConfigModalProps> = ({
  isOpen,
  onClose,
  programId,
  programName,
  programType,
  initialLevels,
  onSave,
  maxDepth,
  hasExistingNodes = false,
  existingNodeCount = 0,
}) => {
  const effectiveMaxDepth = maxDepth ?? DEFAULT_MAX_DEPTH;
  const [levels, setLevels] = useState<HierarchyLevelConfig[]>([]);
  const [expandedLevel, setExpandedLevel] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasChanges, setHasChanges] = useState(false);

  // Initialize levels
  useEffect(() => {
    if (initialLevels && initialLevels.length > 0) {
      setLevels(initialLevels);
    } else {
      // Default 7-level structure
      setLevels([
        { levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY', isRequired: true },
        { levelNumber: 2, levelName: 'Region', dimensionType: 'REGION', isRequired: true },
        { levelNumber: 3, levelName: 'Country', dimensionType: 'COUNTRY', isRequired: true },
        { levelNumber: 4, levelName: 'Entity', dimensionType: 'ENTITY', isRequired: true },
        { levelNumber: 5, levelName: 'Department', dimensionType: 'DEPARTMENT', isRequired: false },
        { levelNumber: 6, levelName: 'Account Type', dimensionType: 'ACCOUNT_TYPE', isRequired: true },
        { levelNumber: 7, levelName: 'Virtual Account', dimensionType: 'VIRTUAL_ACCOUNT', isRequired: true },
      ]);
    }
    setHasChanges(false);
  }, [initialLevels, isOpen]);

  const handleLevelChange = (index: number, updatedLevel: HierarchyLevelConfig) => {
    const newLevels = [...levels];
    newLevels[index] = updatedLevel;
    setLevels(newLevels);
    setHasChanges(true);
  };

  const handleDeleteLevel = (index: number) => {
    if (levels.length <= 2) return; // Minimum 2 levels required
    const newLevels = levels.filter((_, i) => i !== index).map((l, i) => ({ ...l, levelNumber: i + 1 }));
    setLevels(newLevels);
    setHasChanges(true);
  };

  const handleAddLevel = () => {
    if (levels.length >= effectiveMaxDepth) return;
    const newLevel: HierarchyLevelConfig = {
      levelNumber: levels.length + 1,
      levelName: `Level ${levels.length + 1}`,
      dimensionType: 'ENTITY',
      isRequired: false,
    };
    setLevels([...levels, newLevel]);
    setExpandedLevel(levels.length);
    setHasChanges(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave(levels);
      setHasChanges(false);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save configuration');
    } finally {
      setSaving(false);
    }
  };

  const handleClose = () => {
    if (hasChanges) {
      if (confirm('You have unsaved changes. Are you sure you want to close?')) {
        onClose();
      }
    } else {
      onClose();
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title={
        <div className="flex items-center gap-2">
          <Settings className="w-5 h-5 text-primary-600 dark:text-primary-200" />
          <span>Hierarchy Level Configuration</span>
        </div>
      }
      size="lg"
    >
      <div className="p-4 space-y-4">
        {/* Header Info */}
        <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{programName || 'Program'}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                Configure the hierarchy levels and allowed values for virtual accounts
              </p>
            </div>
            {programType && <Badge variant="info">{programType}</Badge>}
          </div>
        </div>

        {/* Info Box */}
        <div className="bg-info-50 border border-info-200 rounded-lg p-3 flex gap-2 dark:bg-info-500/10 dark:border-info-500/30">
          <Info className="w-4 h-4 text-info-600 flex-shrink-0 mt-0.5 dark:text-info-300" />
          <div className="text-sm text-info-700 dark:text-info-300">
            <p className="font-medium">How Allowed Values Work</p>
            <ul className="mt-1 space-y-0.5 text-xs">
              <li>• Define what values are valid for each hierarchy level</li>
              <li>• When creating VAs, users can only select from these values</li>
              <li>• Leave empty to allow any value (free-form input)</li>
              <li>• Values are case-insensitive (stored uppercase)</li>
            </ul>
          </div>
        </div>

        {/* Warning: Existing Nodes */}
        {hasExistingNodes && (
          <div className="bg-warning-50 border border-warning-300 rounded-lg p-3 flex gap-2 dark:bg-warning-500/10">
            <AlertCircle className="w-4 h-4 text-warning-600 flex-shrink-0 mt-0.5 dark:text-warning-300" />
            <div className="text-sm text-warning-800 dark:text-warning-300">
              <p className="font-medium">Hierarchy Already Has Nodes</p>
              <p className="mt-1 text-xs text-warning-700 dark:text-warning-300">
                This program has {existingNodeCount > 0 ? `${existingNodeCount} existing hierarchy nodes` : 'existing hierarchy nodes'}.
                Modifying level configurations may cause inconsistencies with existing data.
                Changes to allowed values will only affect new nodes - existing nodes will retain their current values.
              </p>
              <p className="mt-1 text-xs text-warning-600 font-medium dark:text-warning-300">
                It is recommended to configure levels during program setup, before creating hierarchy nodes.
              </p>
            </div>
          </div>
        )}

        {/* Error Display */}
        {error && (
          <div className="bg-error-50 border border-error-200 rounded-lg p-3 flex gap-2 dark:bg-error-500/10 dark:border-error-500/30">
            <AlertCircle className="w-4 h-4 text-error-600 flex-shrink-0 dark:text-error-300" />
            <p className="text-sm text-error-700 dark:text-error-300">{error}</p>
          </div>
        )}

        {/* Level List */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-neutral-700 dark:text-neutral-200">
              Hierarchy Levels ({levels.length}/{effectiveMaxDepth})
            </h3>
            {levels.length < effectiveMaxDepth && (
              <Button size="sm" variant="outline" onClick={handleAddLevel}>
                <Plus className="w-4 h-4 mr-1" />
                Add Level
              </Button>
            )}
          </div>

          <div className="space-y-2 max-h-[400px] overflow-y-auto pr-1">
            {levels.map((level, index) => (
              <LevelEditor
                key={level.levelNumber}
                level={level}
                onChange={(updated) => handleLevelChange(index, updated)}
                isExpanded={expandedLevel === index}
                onToggleExpand={() => setExpandedLevel(expandedLevel === index ? null : index)}
                canDelete={levels.length > 2 && index !== 0 && index !== levels.length - 1}
                onDelete={() => handleDeleteLevel(index)}
              />
            ))}
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between pt-4 border-t">
          <div className="flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
            {hasChanges ? (
              <>
                <AlertCircle className="w-3 h-3 text-warning-500" />
                <span className="text-warning-600 dark:text-warning-300">Unsaved changes</span>
              </>
            ) : (
              <>
                <CheckCircle className="w-3 h-3 text-success-500" />
                <span>All changes saved</span>
              </>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={handleClose}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={saving || !hasChanges}>
              {saving ? (
                <>
                  <RefreshCw className="w-4 h-4 mr-1 animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <Save className="w-4 h-4 mr-1" />
                  Save Configuration
                </>
              )}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default HierarchyLevelConfigModal;
