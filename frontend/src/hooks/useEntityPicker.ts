import { useCallback } from 'react';
import { useUser, UserEntity } from '../context/UserContext';

export interface EntityPickerState {
  // Current selections
  currentCorporateId: string | null;
  currentEntityId: string | null;
  currentEntity: UserEntity | null;

  // Available options
  corporates: Array<{ id: string; name: string; code: string }>;
  entities: UserEntity[];

  // Filtered entities (for current corporate)
  filteredEntities: UserEntity[];

  // Treasury entity (if exists in current corporate)
  treasuryEntity: UserEntity | null;

  // Subsidiary entities (non-treasury)
  subsidiaryEntities: UserEntity[];

  // Actions
  switchCorporate: (corporateId: string) => void;
  switchEntity: (entityId: string) => void;

  // Loading
  isLoading: boolean;
}

export const useEntityPicker = (): EntityPickerState => {
  const {
    currentCorporateId,
    currentEntityId,
    currentEntity,
    corporates,
    entities,
    switchCorporate,
    switchEntity,
    isLoading,
  } = useUser();

  // Filter entities for current corporate
  const filteredEntities = entities.filter(e => e.corporateId === currentCorporateId);

  // Find treasury entity
  const treasuryEntity = filteredEntities.find(e => e.isTreasuryCenter || (e.canLend && !e.canBorrow)) || null;

  // Get subsidiary entities (non-treasury)
  const subsidiaryEntities = filteredEntities.filter(e => !e.isTreasuryCenter && !(e.canLend && !e.canBorrow));

  return {
    currentCorporateId,
    currentEntityId,
    currentEntity,
    corporates,
    entities,
    filteredEntities,
    treasuryEntity,
    subsidiaryEntities,
    switchCorporate,
    switchEntity,
    isLoading,
  };
};

// Helper hook to get entities for dropdowns
export const useEntityOptions = () => {
  const { filteredEntities, treasuryEntity, subsidiaryEntities } = useEntityPicker();

  return {
    allEntities: filteredEntities,
    treasuryEntities: treasuryEntity ? [treasuryEntity] : [],
    subsidiaryEntities,
    lenderEntities: filteredEntities.filter(e => e.canLend),
    borrowerEntities: filteredEntities.filter(e => e.canBorrow),
  };
};
