import React, { useState, useEffect, useCallback } from 'react';
import { corporatesApi, legalEntityApi } from '../services/api';
import { UserContext, UserContextType, UserEntity } from './useUser';

/**
 * UserProvider — owns the current corporate/entity selection.
 *
 * The context object, its types and the `useUser` hook live in `./useUser`.
 */

export const UserProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentCorporateId, setCurrentCorporateId] = useState<string | null>(
    localStorage.getItem('current_corporate_id')
  );
  const [currentEntityId, setCurrentEntityId] = useState<string | null>(
    localStorage.getItem('current_entity_id')
  );
  const [corporates, setCorporates] = useState<Array<{ id: string; name: string; code: string }>>([]);
  const [entities, setEntities] = useState<UserEntity[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const response = await corporatesApi.getAll();
        const corpList = response.data || [];
        setCorporates(corpList.map((c: any) => ({ id: c.id, name: c.name, code: c.code })));

        // Auto-select first corporate if none selected
        if (!currentCorporateId && corpList.length > 0) {
          setCurrentCorporateId(corpList[0].id);
          localStorage.setItem('current_corporate_id', corpList[0].id);
        }
      } catch (error) {
        console.error('Failed to load corporates:', error);
      }
    };
    loadCorporates();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mount-only fetch; currentCorporateId is read once as an "already chosen?" guard and written by this effect, so depending on it would refetch the corporate list on every switch.
  }, []);

  // Load entities when corporate changes
  useEffect(() => {
    if (!currentCorporateId) {
      setEntities([]);
      return;
    }

    const loadEntities = async () => {
      setIsLoading(true);
      try {
        const response = await legalEntityApi.getByCorporate(currentCorporateId);
        const entityList = (response.data || []).map((e: any) => ({
          id: e.id,
          entityCode: e.entityCode,
          entityName: e.entityName,
          entityType: e.entityType,
          isTreasuryCenter: e.isTreasuryCenter || false,
          canLend: e.canLend || false,
          canBorrow: e.canBorrow || false,
          ihbEnabled: e.ihbEnabled || false,
          corporateId: currentCorporateId,
        }));
        setEntities(entityList);

        // Auto-select first entity if none selected or current not in list
        if (!currentEntityId || !entityList.find((e: UserEntity) => e.id === currentEntityId)) {
          if (entityList.length > 0) {
            setCurrentEntityId(entityList[0].id);
            localStorage.setItem('current_entity_id', entityList[0].id);
          }
        }
      } catch (error) {
        console.error('Failed to load entities:', error);
      } finally {
        setIsLoading(false);
      }
    };
    loadEntities();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refetch on corporate change only; currentEntityId is read as an "is the selection still valid?" guard and written here, so depending on it would refetch the entity list every time the user switches entity.
  }, [currentCorporateId]);

  const switchCorporate = useCallback((corporateId: string) => {
    setCurrentCorporateId(corporateId);
    setCurrentEntityId(null);
    localStorage.setItem('current_corporate_id', corporateId);
    localStorage.removeItem('current_entity_id');
  }, []);

  const switchEntity = useCallback((entityId: string) => {
    setCurrentEntityId(entityId);
    localStorage.setItem('current_entity_id', entityId);
  }, []);

  const currentEntity = entities.find(e => e.id === currentEntityId) || null;

  // Persona helpers
  const isTreasury = currentEntity?.isTreasuryCenter || (currentEntity?.canLend && !currentEntity?.canBorrow) || false;
  const isSubsidiary = !isTreasury && (currentEntity?.entityType === 'SUBSIDIARY' || currentEntity?.canBorrow) || false;
  const canApproveRecharges = isTreasury;
  const canManageNetting = isTreasury;
  const canManageIhb = isTreasury || currentEntity?.canLend || false;

  const value: UserContextType = {
    currentCorporateId,
    currentEntityId,
    currentEntity,
    corporates,
    entities,
    switchCorporate,
    switchEntity,
    isTreasury,
    isSubsidiary,
    canApproveRecharges,
    canManageNetting,
    canManageIhb,
    isLoading,
  };

  return <UserContext.Provider value={value}>{children}</UserContext.Provider>;
};
