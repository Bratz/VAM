import React from 'react';
import { useEntityPicker } from '../../hooks/useEntityPicker';
import { Building2, ChevronDown } from 'lucide-react';

interface EntityPickerProps {
  showCorporate?: boolean;
  showEntity?: boolean;
  /**
   * Render the "Treasury" / "Subsidiary" role pill after the selectors.
   * Defaults to true (legacy behaviour). The Aperture header passes false
   * because that information is already shown in the user chip.
   */
  showRoleBadge?: boolean;
  compact?: boolean;
  className?: string;
}

export const EntityPicker: React.FC<EntityPickerProps> = ({
  showCorporate = true,
  showEntity = true,
  showRoleBadge = true,
  compact = false,
  className = '',
}) => {
  const {
    currentCorporateId,
    currentEntityId,
    currentEntity,
    corporates,
    filteredEntities,
    switchCorporate,
    switchEntity,
    isLoading,
  } = useEntityPicker();

  if (isLoading) {
    return (
      <div className={`flex items-center gap-2 ${className}`}>
        <div className="animate-pulse bg-neutral-200 h-8 w-32 rounded dark:bg-primary-800" />
      </div>
    );
  }

  return (
    <div className={`flex items-center gap-2 ${className}`}>
      {showCorporate && (
        <div className="relative">
          <select
            value={currentCorporateId || ''}
            onChange={(e) => switchCorporate(e.target.value)}
            className={`appearance-none bg-white border border-neutral-300 rounded-lg pr-8 pl-3 ${
              compact ? 'py-1 text-sm' : 'py-2'
            } focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:bg-primary-900 dark:border-primary-700`}
          >
            {corporates.map((corp) => (
              <option key={corp.id} value={corp.id}>
                {corp.code} - {corp.name}
              </option>
            ))}
          </select>
          <ChevronDown className="absolute right-2 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400 pointer-events-none dark:text-neutral-500" />
        </div>
      )}

      {showEntity && (
        <div className="relative">
          <select
            value={currentEntityId || ''}
            onChange={(e) => switchEntity(e.target.value)}
            className={`appearance-none bg-white border border-neutral-300 rounded-lg pr-8 pl-3 ${
              compact ? 'py-1 text-sm' : 'py-2'
            } focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:bg-primary-900 dark:border-primary-700`}
          >
            {filteredEntities.map((entity) => (
              <option key={entity.id} value={entity.id}>
                {entity.entityCode} - {entity.entityName}
                {entity.isTreasuryCenter ? ' (Treasury)' : ''}
              </option>
            ))}
          </select>
          <ChevronDown className="absolute right-2 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400 pointer-events-none dark:text-neutral-500" />
        </div>
      )}

      {showRoleBadge && currentEntity && (
        <div className={`flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium ${currentEntity.isTreasuryCenter ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-300'}`}>
          <Building2 className="w-3 h-3" />
          {currentEntity.isTreasuryCenter ? 'Treasury' : 'Subsidiary'}
        </div>
      )}
    </div>
  );
};

export default EntityPicker;
