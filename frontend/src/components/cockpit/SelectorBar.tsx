import React from 'react';
import { Building2, Briefcase, X } from 'lucide-react';
import { Select, Button } from '../ui';

// ============================================================================
// Cockpit — inline entity selector.
//
// Intentionally a SEPARATE variant from the page-scoped `<ScopeSelector>`
// primitive in `components/layout/ScopeSelector.tsx`. The two solve different
// layout problems:
//
//   - ScopeSelector       — page-scoped picker in its own Card row.
//                           Used on PhysicalAccounts, Accounts, Intercompany,
//                           NotionalPooling, InHouseBank, EntityBalanceTree.
//
//   - This (cockpit)      — bare-flex inline picker living in the cockpit's
//                           GreetingStrip alongside the greeting, time, market
//                           session, refresh button, and switch-to-classic
//                           link. Cannot use a Card wrapper because it sits
//                           inside another compound row.
//
// Same design-system vocabulary (Select with selectSize='sm', semantic palette
// only, full dark-mode parity), just no surrounding Card. If a future
// layout shift makes the cockpit's picker its own row, switch this file to
// re-export ScopeSelector with mode='corporate-program'.
// ============================================================================

export interface CockpitSelectorBarProps {
  corporates: Array<{ id: string; name?: string; legalName?: string }>;
  programs: Array<{ id: string; programName: string; programType: string }>;
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  loading?: boolean;
}

export const SelectorBar: React.FC<CockpitSelectorBarProps> = ({
  corporates,
  programs,
  selectedCorporateId,
  selectedProgramId,
  onCorporateChange,
  onProgramChange,
  loading,
}) => {
  const corporateOptions = [
    { value: '', label: `All Corporates (${corporates.length})` },
    ...corporates.map((c) => ({ value: c.id, label: c.name || c.legalName || 'Unnamed' })),
  ];
  const programOptions = [
    { value: '', label: `All Programs (${programs.length})` },
    ...programs.map((p) => ({ value: p.id, label: `${p.programName} (${p.programType})` })),
  ];

  return (
    <div className="flex items-center gap-3 flex-wrap">
      <div className="flex items-center gap-2">
        <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" aria-hidden />
        <Select
          value={selectedCorporateId}
          onChange={(e) => onCorporateChange(e.target.value)}
          options={corporateOptions}
          disabled={loading}
          selectSize="sm"
          className="min-w-[180px]"
          aria-label="Corporate"
        />
      </div>
      <div className="flex items-center gap-2">
        <Briefcase className="w-4 h-4 text-accent-600 dark:text-accent-300" aria-hidden />
        <Select
          value={selectedProgramId}
          onChange={(e) => onProgramChange(e.target.value)}
          options={programOptions}
          disabled={loading}
          selectSize="sm"
          className="min-w-[200px]"
          aria-label="Program"
        />
      </div>
      {(selectedCorporateId || selectedProgramId) && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => { onCorporateChange(''); onProgramChange(''); }}
          leftIcon={<X className="w-4 h-4" />}
        >
          Clear
        </Button>
      )}
    </div>
  );
};
