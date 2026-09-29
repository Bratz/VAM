import { createContext, useContext } from 'react';

/**
 * Context object, types + consumer hook for the current corporate/entity
 * selection. The provider component lives in `UserContext.tsx`; these live here
 * so that file can export a component alone and keep Fast Refresh working.
 */

export interface UserEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'TREASURY_CENTER' | 'DIVISION';
  isTreasuryCenter: boolean;
  canLend: boolean;
  canBorrow: boolean;
  ihbEnabled: boolean;
  corporateId: string;
}

export interface UserContextType {
  // Current selections
  currentCorporateId: string | null;
  currentEntityId: string | null;
  currentEntity: UserEntity | null;

  // Available options
  corporates: Array<{ id: string; name: string; code: string }>;
  entities: UserEntity[];

  // Actions
  switchCorporate: (corporateId: string) => void;
  switchEntity: (entityId: string) => void;

  // Persona helpers
  isTreasury: boolean;
  isSubsidiary: boolean;
  canApproveRecharges: boolean;
  canManageNetting: boolean;
  canManageIhb: boolean;

  // Loading state
  isLoading: boolean;
}

export const UserContext = createContext<UserContextType | null>(null);

export const useUser = (): UserContextType => {
  const context = useContext(UserContext);
  if (!context) {
    throw new Error('useUser must be used within a UserProvider');
  }
  return context;
};

export default UserContext;
