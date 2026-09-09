import React from 'react';
import { Building, Briefcase, Users, RefreshCw, Loader2, X } from 'lucide-react';
import { cn } from '../../utils';
import { Card, Badge, Button, Select, StatusIconBadge } from '../ui';

// ============================================================================
// ScopeSelector — single canonical corporate / program / entity picker.
//
// Replaces the hand-rolled `SelectorBar` and `CorporateEntityFilterBar` copies
// that had drifted across the app. Now consumed by every corporate-scoped
// page in Aperture.
//
// Four prop shapes via discriminated union:
//   mode='corporate-program'  → Corporate dropdown + Program dropdown
//   mode='corporate-entity'   → Corporate dropdown + Legal-Entity dropdown
//   mode='corporate-only'     → Corporate dropdown alone (e.g. Shadow Accounts,
//                               Programs page) — child scope is implicit
//   mode='entity-only'        → Legal-Entity dropdown alone (e.g. Account
//                               Linking) — corporate scope handled upstream
//
// Common props (loading, onRefresh/refreshing, rightSlot, className) apply
// to all four modes. Active-selection chip row + Clear button auto-adapt
// per mode.
//
// Visual recipe (matches every conformed page):
//   - <Card padding="sm"> wrapper, flat primary-50/40 tint
//     (no gradient — gradients were one of the divergence vectors)
//   - <StatusIconBadge> medallion per field, tone matches the field
//   - .label uppercase eyebrow
//   - <Select> primitive (selectSize='sm') for the dropdowns
//   - Vertical divider between fields
//
// Light + dark mode: every surface, border, text, divider, and chip carries
// dark variants drawn from the semantic palette only — primary-*, info-*,
// accent-*, neutral-*. No chromatic-named classes.
// ============================================================================

export interface ScopeCorporate {
  id: string;
  legalName?: string;
  tradeName?: string;
  shortName?: string;
  // Allow callers to pass any extra fields without making TS complain.
  [extra: string]: any;
}

export interface ScopeProgram {
  id: string;
  programName?: string;
  name?: string;
  programType?: string;
  currencyCode?: string;
  [extra: string]: any;
}

export interface ScopeLegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  isTreasuryCenter?: boolean;
  status?: string;
  canHoldPhysicalAccounts?: boolean;
  [extra: string]: any;
}

interface CommonProps {
  loading?: boolean;
  /** Hide the active-selection chip row on the right. */
  hideActiveChips?: boolean;
  /** Refresh button on the far right — Dashboard / EntityBalanceTree pattern. */
  onRefresh?: () => void;
  refreshing?: boolean;
  /** Extra trailing content (e.g. a 3rd dropdown). Rendered just before refresh. */
  rightSlot?: React.ReactNode;
  className?: string;
  /**
   * Density pass: render without the Card wrapper, the "Corporate" eyebrow
   * label, and the "Showing all" hint — just the bare dropdown(s). For
   * callers that already compose their own chrome around the selector (e.g.
   * the dashboard's merged tab+scope row) and don't need the concept
   * re-labelled. Every other consumer is unaffected (defaults to false).
   */
  bare?: boolean;
}

interface CorporateProgramProps extends CommonProps {
  mode: 'corporate-program';
  corporates: ScopeCorporate[];
  programs: ScopeProgram[];
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  /** Disable the Program select until a Corporate is chosen. */
  disableChildUntilParent?: boolean;
}

interface CorporateEntityProps extends CommonProps {
  mode: 'corporate-entity';
  corporates: ScopeCorporate[];
  legalEntities: ScopeLegalEntity[];
  selectedCorporateId: string;
  selectedEntityId: string;
  onCorporateChange: (id: string) => void;
  onEntityChange: (id: string) => void;
  /**
   * Filter the entity list before rendering (e.g. PhysicalAccountsPage
   * filters to `canHoldPhysicalAccounts`). Defaults to "ACTIVE status only".
   */
  entityFilter?: (e: ScopeLegalEntity) => boolean;
  /** Disable the Entity select until a Corporate is chosen. */
  disableChildUntilParent?: boolean;
  /** Optional label override for the entity field (e.g. "Entity (Optional)"). */
  entityLabel?: string;
}

interface CorporateOnlyProps extends CommonProps {
  mode: 'corporate-only';
  corporates: ScopeCorporate[];
  selectedCorporateId: string;
  onCorporateChange: (id: string) => void;
  /** Override the "All Corporates" placeholder (e.g. "Select Corporate..."). */
  allLabel?: string;
  /**
   * If true, the Corporate dropdown won't include the "All Corporates"
   * option — the user must pick one. Useful for pages whose backend
   * requires a single corporate (e.g. Shadow Accounts today).
   */
  requireSelection?: boolean;
}

interface EntityOnlyProps extends CommonProps {
  mode: 'entity-only';
  legalEntities: ScopeLegalEntity[];
  selectedEntityId: string;
  onEntityChange: (id: string) => void;
  entityFilter?: (e: ScopeLegalEntity) => boolean;
  entityLabel?: string;
  /** Override the "All Entities" placeholder. */
  allLabel?: string;
  requireSelection?: boolean;
}

export type ScopeSelectorProps =
  | CorporateProgramProps
  | CorporateEntityProps
  | CorporateOnlyProps
  | EntityOnlyProps;

// ----------------------------------------------------------------------------

const corporateLabel = (c: ScopeCorporate): string =>
  c.tradeName || c.legalName || c.shortName || c.id;

const programLabel = (p: ScopeProgram): string => {
  const name = p.programName || p.name || p.id;
  if (p.currencyCode) return `${name} (${p.currencyCode})`;
  if (p.programType) return `${name} (${p.programType})`;
  return name;
};

const entityLabelText = (e: ScopeLegalEntity): string =>
  `${e.entityCode} – ${e.entityName}${e.isTreasuryCenter ? ' ⭐' : ''}`;

// Vertical divider styled for both themes.
const Divider: React.FC = () => (
  <div className="hidden sm:block h-10 w-px bg-primary-200 dark:bg-primary-700" aria-hidden />
);

// ----------------------------------------------------------------------------

export const ScopeSelector: React.FC<ScopeSelectorProps> = (props) => {
  const {
    loading,
    hideActiveChips,
    onRefresh,
    refreshing,
    rightSlot,
    className,
    bare,
  } = props;

  // ---- Compute per-mode option lists, child node, active chips ----

  let corporateField: React.ReactNode = null;
  let childField: React.ReactNode = null;
  let activeCorporateChip: React.ReactNode = null;
  let activeChildChip: React.ReactNode = null;
  let placeholderHint: React.ReactNode = null;
  let anySelection = false;
  let handleClear: () => void = () => {};

  // Corporate dropdown — shared by 3 of 4 modes.
  if (props.mode === 'corporate-program' || props.mode === 'corporate-entity' || props.mode === 'corporate-only') {
    const selectedCorporate = props.corporates.find((c) => c.id === props.selectedCorporateId);
    const requireSelection = props.mode === 'corporate-only' && (props as CorporateOnlyProps).requireSelection;
    const allLabel = props.mode === 'corporate-only' ? (props as CorporateOnlyProps).allLabel : undefined;
    const corporateOptions = [
      ...(requireSelection
        ? [{ value: '', label: allLabel ?? 'Select Corporate…' }]
        : [{ value: '', label: allLabel ?? `All Corporates${props.corporates.length ? ` (${props.corporates.length})` : ''}` }]),
      ...props.corporates.map((c) => ({ value: c.id, label: corporateLabel(c) })),
    ];

    corporateField = (
      <div className="flex items-center gap-2">
        <StatusIconBadge tone="primary" icon={Building} size="sm" />
        <div className="min-w-[220px]">
          {!bare && <label className="label">Corporate</label>}
          <Select
            value={props.selectedCorporateId}
            onChange={(e) => props.onCorporateChange(e.target.value)}
            options={corporateOptions}
            disabled={loading}
            selectSize="sm"
            aria-label="Corporate"
          />
        </div>
      </div>
    );

    if (selectedCorporate) {
      activeCorporateChip = (
        <Badge variant="primary" size="sm">
          <Building className="w-3 h-3" />
          {selectedCorporate.shortName || corporateLabel(selectedCorporate)}
        </Badge>
      );
    }
  }

  // Program child — only in corporate-program.
  if (props.mode === 'corporate-program') {
    const programOptions = [
      { value: '', label: `All Programs${props.programs.length ? ` (${props.programs.length})` : ''}` },
      ...props.programs.map((p) => ({ value: p.id, label: programLabel(p) })),
    ];
    const childDisabled = loading
      || (props.disableChildUntilParent && !props.selectedCorporateId);
    const selectedProgram = props.programs.find((p) => p.id === props.selectedProgramId);

    childField = (
      <div className="flex items-center gap-2">
        <StatusIconBadge tone="info" icon={Briefcase} size="sm" />
        <div className="min-w-[200px]">
          <label className="label">Program</label>
          <Select
            value={props.selectedProgramId}
            onChange={(e) => props.onProgramChange(e.target.value)}
            options={programOptions}
            disabled={childDisabled}
            selectSize="sm"
            aria-label="Program"
          />
        </div>
      </div>
    );

    if (selectedProgram) {
      activeChildChip = (
        <Badge variant="info" size="sm">
          <Briefcase className="w-3 h-3" />
          {selectedProgram.programName || selectedProgram.name}
        </Badge>
      );
    }
  }

  // Entity child — corporate-entity OR entity-only.
  if (props.mode === 'corporate-entity' || props.mode === 'entity-only') {
    const baseFilter = props.entityFilter ?? ((e: ScopeLegalEntity) => e.status === 'ACTIVE');
    const eligibleEntities = props.legalEntities.filter(baseFilter);
    const requireSelection = props.mode === 'entity-only' && (props as EntityOnlyProps).requireSelection;
    const allLabel = props.mode === 'entity-only' ? (props as EntityOnlyProps).allLabel : undefined;
    const entityOptions = [
      ...(requireSelection
        ? [{ value: '', label: allLabel ?? 'Select Entity…' }]
        : [{ value: '', label: allLabel ?? `All Entities${eligibleEntities.length ? ` (${eligibleEntities.length})` : ''}` }]),
      ...eligibleEntities.map((e) => ({ value: e.id, label: entityLabelText(e) })),
    ];
    const childDisabled = loading
      || (props.mode === 'corporate-entity' && props.disableChildUntilParent && !props.selectedCorporateId);
    const selectedEntity = props.legalEntities.find((e) => e.id === props.selectedEntityId);

    childField = (
      <div className="flex items-center gap-2">
        <StatusIconBadge tone="accent" icon={Users} size="sm" />
        <div className="min-w-[220px]">
          <label className="label">{props.entityLabel ?? 'Entity'}</label>
          <Select
            value={props.selectedEntityId}
            onChange={(e) => props.onEntityChange(e.target.value)}
            options={entityOptions}
            disabled={childDisabled}
            selectSize="sm"
            aria-label="Legal entity"
          />
        </div>
      </div>
    );

    if (selectedEntity) {
      activeChildChip = (
        <Badge variant="info" size="sm">
          <Users className="w-3 h-3" />
          {selectedEntity.entityCode}
        </Badge>
      );
    }
  }

  // ---- Determine "any selection" + clear handler per mode. ----
  switch (props.mode) {
    case 'corporate-program':
      anySelection = !!props.selectedCorporateId || !!props.selectedProgramId;
      handleClear = () => { props.onCorporateChange(''); props.onProgramChange(''); };
      break;
    case 'corporate-entity':
      anySelection = !!props.selectedCorporateId || !!props.selectedEntityId;
      handleClear = () => { props.onCorporateChange(''); props.onEntityChange(''); };
      break;
    case 'corporate-only':
      anySelection = !!props.selectedCorporateId;
      handleClear = () => props.onCorporateChange('');
      break;
    case 'entity-only':
      anySelection = !!props.selectedEntityId;
      handleClear = () => props.onEntityChange('');
      break;
  }

  // ---- "Showing all" empty-state hint. Suppressed if requireSelection is set. ----
  const noSelectionAtAll =
    (props.mode === 'corporate-program' || props.mode === 'corporate-entity' || props.mode === 'corporate-only')
      ? !props.selectedCorporateId
      : !props.selectedEntityId;
  const requireSelection =
    (props.mode === 'corporate-only' && (props as CorporateOnlyProps).requireSelection)
    || (props.mode === 'entity-only' && (props as EntityOnlyProps).requireSelection);
  if (noSelectionAtAll && !requireSelection && !bare) {
    placeholderHint = (
      <span className="body-sm text-neutral-500 italic dark:text-neutral-400">
        Showing all
      </span>
    );
  }

  const content = (
    <div className={cn('flex items-center gap-4 flex-wrap', bare && className)}>
      {/* Corporate (3 of 4 modes) */}
      {corporateField}

      {/* Divider only when both fields render. entity-only renders just
          the child; corporate-only renders just the parent. */}
      {corporateField && childField && <Divider />}

      {/* Program / Entity child (3 of 4 modes) */}
      {childField}

      {/* Optional extension slot (e.g. EntityBalanceTreePage's currency). */}
      {rightSlot && (
        <>
          <Divider />
          {rightSlot}
        </>
      )}

      {/* Active-selection chips + clear button. */}
      {!hideActiveChips && (
        <div className="flex items-center gap-2 ml-auto flex-wrap">
          {activeCorporateChip}
          {activeChildChip}
          {placeholderHint}
          {anySelection && (
            <Button
              variant="ghost"
              size="sm"
              onClick={handleClear}
              leftIcon={<X className="w-3.5 h-3.5" />}
              aria-label="Clear scope"
            >
              Clear
            </Button>
          )}
        </div>
      )}

      {/* Refresh button — optional. When loading, the icon spins. */}
      {onRefresh && (
        <Button
          variant="outline"
          size="sm"
          onClick={onRefresh}
          disabled={refreshing || loading}
          leftIcon={<RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />}
          aria-label="Refresh"
        >
          <span className="hidden sm:inline">Refresh</span>
        </Button>
      )}

      {/* In-flight indicator when no Refresh button is mounted. */}
      {!onRefresh && loading && (
        <Loader2 className="w-5 h-5 text-primary-600 animate-spin dark:text-primary-200" aria-label="Loading" />
      )}
    </div>
  );

  if (bare) return content;

  return (
    <Card
      padding="sm"
      className={cn(
        // Flat tint instead of the gradient that varied across pre-conformance copies.
        // Both light and dark are derived from the semantic primary palette.
        'bg-primary-50/40 border-primary-200 dark:bg-primary-800/30 dark:border-primary-700',
        className,
      )}
    >
      {content}
    </Card>
  );
};
