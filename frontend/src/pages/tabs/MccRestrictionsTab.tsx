// ============================================================================
// MCC RESTRICTIONS TAB
// MCC whitelist/blacklist, merchant whitelist, country restrictions
// ============================================================================

import React, { useState, useCallback } from 'react';
import { 
  Shield, 
  Plus, 
  X, 
  CheckCircle, 
  Ban, 
  Globe,
  Store,
  Info,
  Search,
} from 'lucide-react';
import { Input, Button, Badge } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { CreateVaRequest, Program, ProgramTypeConfig } from '../vaTypes';
import { cn } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface MccRestrictionsTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  config?: ProgramTypeConfig;
}

// ============================================================================
// MCC CATEGORY DATA
// ============================================================================

const MCC_CATEGORIES = [
  { 
    category: 'Travel & Entertainment',
    codes: [
      { code: '3000-3299', label: 'Airlines' },
      { code: '3351-3441', label: 'Car Rentals' },
      { code: '3501-3790', label: 'Hotels & Lodging' },
      { code: '4111', label: 'Local Transportation' },
      { code: '4511', label: 'Airlines' },
      { code: '7011', label: 'Hotels & Motels' },
      { code: '7512', label: 'Car Rentals' },
    ]
  },
  {
    category: 'Retail',
    codes: [
      { code: '5200', label: 'Home Supply Stores' },
      { code: '5311', label: 'Department Stores' },
      { code: '5411', label: 'Grocery Stores' },
      { code: '5541', label: 'Gas Stations' },
      { code: '5651', label: 'Clothing Stores' },
      { code: '5732', label: 'Electronics Stores' },
      { code: '5812', label: 'Restaurants' },
      { code: '5814', label: 'Fast Food' },
    ]
  },
  {
    category: 'Services',
    codes: [
      { code: '4900', label: 'Utilities' },
      { code: '6011', label: 'ATM/Cash' },
      { code: '6300', label: 'Insurance' },
      { code: '7230', label: 'Beauty Shops' },
      { code: '7941', label: 'Sports Clubs' },
      { code: '8011', label: 'Doctors' },
      { code: '8021', label: 'Dentists' },
      { code: '8062', label: 'Hospitals' },
    ]
  },
  {
    category: 'High Risk (Often Blocked)',
    codes: [
      { code: '5912', label: 'Drug Stores' },
      { code: '5921', label: 'Liquor Stores' },
      { code: '5993', label: 'Tobacco Stores' },
      { code: '7273', label: 'Dating Services' },
      { code: '7995', label: 'Gambling' },
      { code: '9402', label: 'Postal Services' },
    ]
  },
];

const COMMON_COUNTRIES = [
  { code: 'US', name: 'United States' },
  { code: 'GB', name: 'United Kingdom' },
  { code: 'DE', name: 'Germany' },
  { code: 'FR', name: 'France' },
  { code: 'NL', name: 'Netherlands' },
  { code: 'AE', name: 'United Arab Emirates' },
  { code: 'SG', name: 'Singapore' },
  { code: 'HK', name: 'Hong Kong' },
  { code: 'JP', name: 'Japan' },
  { code: 'AU', name: 'Australia' },
  { code: 'CA', name: 'Canada' },
  { code: 'CH', name: 'Switzerland' },
];

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

function parseCodes(value: string | undefined): string[] {
  if (!value) return [];
  try {
    return JSON.parse(value);
  } catch {
    return value.split(',').map(s => s.trim()).filter(Boolean);
  }
}

function stringifyCodes(codes: string[]): string {
  return JSON.stringify(codes);
}

// ============================================================================
// CODE TAG LIST COMPONENT
// ============================================================================

interface CodeTagListProps {
  codes: string[];
  onRemove: (code: string) => void;
  // Badge accepts `error`, not `danger` — aligned post-Phase 4 (2026-05-13).
  variant: 'success' | 'error' | 'info';
  emptyMessage: string;
}

const CodeTagList: React.FC<CodeTagListProps> = ({
  codes,
  onRemove,
  variant,
  emptyMessage,
}) => {
  if (codes.length === 0) {
    return (
      <span className="text-xs text-neutral-400 italic">{emptyMessage}</span>
    );
  }

  return (
    <div className="flex flex-wrap gap-2">
      {codes.map(code => (
        <Badge key={code} variant={variant} size="sm" className="pr-1">
          {code}
          <button
            type="button"
            onClick={() => onRemove(code)}
            className="ml-1 p-0.5 rounded hover:bg-black/10 transition-colors"
          >
            <X className="w-3 h-3" />
          </button>
        </Badge>
      ))}
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const MccRestrictionsTab: React.FC<MccRestrictionsTabProps> = ({
  formData,
  setFormData,
  errors,
  program,
  config,
}) => {
  // Input states
  const [mccInput, setMccInput] = useState('');
  const [merchantInput, setMerchantInput] = useState('');
  const [countryInput, setCountryInput] = useState('');
  const [showMccPicker, setShowMccPicker] = useState(false);

  // Parse current values
  const mccWhitelist = parseCodes(formData.mccWhitelist);
  const mccBlacklist = parseCodes(formData.mccBlacklist);
  const merchantWhitelist = parseCodes(formData.merchantWhitelist);
  const countryWhitelist = parseCodes(formData.countryWhitelist);

  // Update helpers
  const updateField = <K extends keyof CreateVaRequest>(
    field: K,
    value: CreateVaRequest[K]
  ) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  const addToList = useCallback((
    field: 'mccWhitelist' | 'mccBlacklist' | 'merchantWhitelist' | 'countryWhitelist',
    value: string,
    current: string[]
  ) => {
    if (value && !current.includes(value)) {
      updateField(field, stringifyCodes([...current, value]));
    }
  }, []);

  const removeFromList = useCallback((
    field: 'mccWhitelist' | 'mccBlacklist' | 'merchantWhitelist' | 'countryWhitelist',
    value: string,
    current: string[]
  ) => {
    updateField(field, stringifyCodes(current.filter(v => v !== value)));
  }, []);

  // Add MCC to whitelist
  const addMccWhitelist = () => {
    if (mccInput.trim()) {
      addToList('mccWhitelist', mccInput.trim(), mccWhitelist);
      setMccInput('');
    }
  };

  // Add MCC to blacklist
  const addMccBlacklist = () => {
    if (mccInput.trim()) {
      addToList('mccBlacklist', mccInput.trim(), mccBlacklist);
      setMccInput('');
    }
  };

  // Quick add common blocklist
  const addCommonBlocklist = () => {
    const commonBlocked = ['5921', '5993', '7995', '7273'];
    const newList = [...new Set([...mccBlacklist, ...commonBlocked])];
    updateField('mccBlacklist', stringifyCodes(newList));
  };

  return (
    <div className="space-y-6">
      {/* MCC Whitelist Section */}
      <div>
        <div className="flex items-center gap-2 mb-3">
          <CheckCircle className="w-5 h-5 text-success-600" />
          <h4 className="text-sm font-medium text-neutral-900">MCC Whitelist</h4>
          <Badge variant="success" size="sm">{mccWhitelist.length} codes</Badge>
        </div>
        <p className="text-xs text-neutral-500 mb-3">
          Only allow transactions at these merchant category codes. Leave empty to allow all.
        </p>
        
        <div className="flex gap-2 mb-3">
          <Input
            value={mccInput}
            onChange={(e) => setMccInput(e.target.value)}
            placeholder="Enter MCC code (e.g., 5411)"
            className="flex-1"
            onKeyDown={(e) => e.key === 'Enter' && addMccWhitelist()}
          />
          <Button size="sm" variant="outline" onClick={addMccWhitelist}>
            <Plus className="w-4 h-4 mr-1" />
            Allow
          </Button>
        </div>

        <CodeTagList
          codes={mccWhitelist}
          onRemove={(code) => removeFromList('mccWhitelist', code, mccWhitelist)}
          variant="success"
          emptyMessage="No whitelist (all MCCs allowed by default)"
        />
      </div>

      {/* MCC Blacklist Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Ban className="w-5 h-5 text-error-600" />
            <h4 className="text-sm font-medium text-neutral-900">MCC Blacklist</h4>
            <Badge variant="error" size="sm">{mccBlacklist.length} codes</Badge>
          </div>
          <Button size="sm" variant="ghost" onClick={addCommonBlocklist}>
            Add Common Blocks
          </Button>
        </div>
        <p className="text-xs text-neutral-500 mb-3">
          Block transactions at these merchant category codes.
        </p>
        
        <div className="flex gap-2 mb-3">
          <Input
            value={mccInput}
            onChange={(e) => setMccInput(e.target.value)}
            placeholder="Enter MCC code (e.g., 7995)"
            className="flex-1"
            onKeyDown={(e) => e.key === 'Enter' && addMccBlacklist()}
          />
          <Button size="sm" variant="danger" onClick={addMccBlacklist}>
            <Ban className="w-4 h-4 mr-1" />
            Block
          </Button>
        </div>

        <CodeTagList
          codes={mccBlacklist}
          onRemove={(code) => removeFromList('mccBlacklist', code, mccBlacklist)}
          variant="error"
          emptyMessage="No blacklist"
        />
      </div>

      {/* MCC Quick Reference */}
      <div className="border-t border-neutral-200 pt-6">
        <button
          type="button"
          onClick={() => setShowMccPicker(!showMccPicker)}
          className="flex items-center gap-2 text-sm font-medium text-primary-600 hover:text-primary-700"
        >
          <Search className="w-4 h-4" />
          {showMccPicker ? 'Hide' : 'Show'} MCC Reference
        </button>

        {showMccPicker && (
          <div className="mt-4 border border-neutral-200 rounded-lg max-h-60 overflow-y-auto">
            {MCC_CATEGORIES.map((category) => (
              <div key={category.category} className="border-b border-neutral-100 last:border-b-0">
                <div className="px-3 py-2 bg-neutral-50 font-medium text-sm">
                  {category.category}
                </div>
                <div className="p-2 flex flex-wrap gap-1">
                  {category.codes.map((mcc) => (
                    <button
                      key={mcc.code}
                      type="button"
                      onClick={() => {
                        const code = mcc.code.includes('-') ? mcc.code.split('-')[0] : mcc.code;
                        addToList('mccWhitelist', code, mccWhitelist);
                      }}
                      className={cn(
                        'px-2 py-1 text-xs rounded border transition-colors',
                        mccWhitelist.includes(mcc.code.split('-')[0])
                          ? 'bg-success-100 border-success-300 text-success-700'
                          : mccBlacklist.includes(mcc.code.split('-')[0])
                          ? 'bg-error-100 border-error-300 text-error-700'
                          : 'border-neutral-200 hover:border-primary-300 hover:bg-primary-50'
                      )}
                      title={mcc.label}
                    >
                      {mcc.code} - {mcc.label}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Merchant Whitelist Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-3">
          <Store className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Merchant Whitelist</h4>
          <Badge variant="info" size="sm">{merchantWhitelist.length} merchants</Badge>
        </div>
        <p className="text-xs text-neutral-500 mb-3">
          Only allow transactions at specific merchants (by merchant ID or name).
        </p>
        
        <div className="flex gap-2 mb-3">
          <Input
            value={merchantInput}
            onChange={(e) => setMerchantInput(e.target.value)}
            placeholder="Enter merchant ID or name"
            className="flex-1"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && merchantInput.trim()) {
                addToList('merchantWhitelist', merchantInput.trim(), merchantWhitelist);
                setMerchantInput('');
              }
            }}
          />
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              if (merchantInput.trim()) {
                addToList('merchantWhitelist', merchantInput.trim(), merchantWhitelist);
                setMerchantInput('');
              }
            }}
          >
            <Plus className="w-4 h-4 mr-1" />
            Add
          </Button>
        </div>

        <CodeTagList
          codes={merchantWhitelist}
          onRemove={(code) => removeFromList('merchantWhitelist', code, merchantWhitelist)}
          variant="info"
          emptyMessage="No merchant restrictions (all merchants allowed)"
        />
      </div>

      {/* Country Whitelist Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-3">
          <Globe className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Country Whitelist</h4>
          <Badge variant="info" size="sm">{countryWhitelist.length} countries</Badge>
        </div>
        <p className="text-xs text-neutral-500 mb-3">
          Only allow transactions in these countries. Leave empty to allow all.
        </p>
        
        <div className="flex gap-2 mb-3">
          <Input
            value={countryInput}
            onChange={(e) => setCountryInput(e.target.value.toUpperCase())}
            placeholder="Country code (e.g., US, GB)"
            maxLength={2}
            className="flex-1"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && countryInput.trim().length === 2) {
                addToList('countryWhitelist', countryInput.trim(), countryWhitelist);
                setCountryInput('');
              }
            }}
          />
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              if (countryInput.trim().length === 2) {
                addToList('countryWhitelist', countryInput.trim(), countryWhitelist);
                setCountryInput('');
              }
            }}
          >
            <Plus className="w-4 h-4 mr-1" />
            Add
          </Button>
        </div>

        <CodeTagList
          codes={countryWhitelist}
          onRemove={(code) => removeFromList('countryWhitelist', code, countryWhitelist)}
          variant="info"
          emptyMessage="No country restrictions (all countries allowed)"
        />

        {/* Quick Country Select */}
        <div className="mt-3 flex flex-wrap gap-1">
          {COMMON_COUNTRIES.map((country) => (
            <button
              key={country.code}
              type="button"
              onClick={() => {
                if (countryWhitelist.includes(country.code)) {
                  removeFromList('countryWhitelist', country.code, countryWhitelist);
                } else {
                  addToList('countryWhitelist', country.code, countryWhitelist);
                }
              }}
              className={cn(
                'px-2 py-1 text-xs rounded border transition-colors',
                countryWhitelist.includes(country.code)
                  ? 'bg-info-100 border-info-300 text-info-700'
                  : 'border-neutral-200 hover:border-primary-300 hover:bg-primary-50'
              )}
              title={country.name}
            >
              {country.code}
            </button>
          ))}
        </div>
      </div>

      {/* Summary */}
      {(mccWhitelist.length > 0 || mccBlacklist.length > 0 || 
        merchantWhitelist.length > 0 || countryWhitelist.length > 0) && (
        <Alert variant="info">
          <Info className="w-4 h-4" />
          <div>
            <strong>Restrictions Summary:</strong>
            <ul className="text-sm mt-1 list-disc list-inside">
              {mccWhitelist.length > 0 && (
                <li>{mccWhitelist.length} MCC(s) whitelisted</li>
              )}
              {mccBlacklist.length > 0 && (
                <li>{mccBlacklist.length} MCC(s) blocked</li>
              )}
              {merchantWhitelist.length > 0 && (
                <li>{merchantWhitelist.length} merchant(s) whitelisted</li>
              )}
              {countryWhitelist.length > 0 && (
                <li>Transactions limited to {countryWhitelist.length} country(s)</li>
              )}
            </ul>
          </div>
        </Alert>
      )}
    </div>
  );
};

export default MccRestrictionsTab;