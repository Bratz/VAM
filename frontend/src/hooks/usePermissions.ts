import { useUser } from '../context/UserContext';

export const usePermissions = () => {
  const { isTreasury, isSubsidiary, canApproveRecharges, canManageNetting, canManageIhb, currentEntity } = useUser();

  return {
    // Entity type checks
    isTreasury,
    isSubsidiary,

    // Feature permissions
    canApproveRecharges,
    canManageNetting,
    canManageIhb,
    canCreateNettingCycle: isTreasury,
    canApproveNettingCycle: isTreasury,
    canSettleNettingCycle: isTreasury,
    canApprovePOBORecharge: isTreasury,
    canRejectPOBORecharge: isTreasury,
    canCreateIHBLoan: currentEntity?.canLend || false,
    canBorrowFromIHB: currentEntity?.canBorrow || false,

    // View permissions
    canViewAllEntities: isTreasury,
    canViewAllTransactions: isTreasury,
    canViewTreasuryDashboard: isTreasury,
  };
};

export const useIsTreasury = () => {
  const { isTreasury } = useUser();
  return isTreasury;
};

export const useIsSubsidiary = () => {
  const { isSubsidiary } = useUser();
  return isSubsidiary;
};

export const useCurrentEntity = () => {
  const { currentEntity } = useUser();
  return currentEntity;
};

export const useCurrentCorporate = () => {
  const { currentCorporateId, corporates } = useUser();
  return corporates.find(c => c.id === currentCorporateId) || null;
};
