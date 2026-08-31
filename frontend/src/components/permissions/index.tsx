import React from 'react';
import { usePermissions } from '../../hooks/usePermissions';

// ============================================================================
// PERMISSION GATE COMPONENTS
// ============================================================================
// Components for conditionally rendering content based on user permissions.
// Use these to gate access to features that require specific permissions.
// ============================================================================

interface PermissionGateProps {
  permission: keyof ReturnType<typeof usePermissions>;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

/**
 * Generic permission gate component.
 * Renders children only if the user has the specified permission.
 *
 * @example
 * <PermissionGate permission="canApproveNettingCycle">
 *   <Button onClick={handleApprove}>Approve</Button>
 * </PermissionGate>
 */
export const PermissionGate: React.FC<PermissionGateProps> = ({
  permission,
  children,
  fallback = null
}) => {
  const permissions = usePermissions();

  if (permissions[permission]) {
    return <>{children}</>;
  }

  return <>{fallback}</>;
};

interface TreasuryOnlyProps {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

/**
 * Renders children only if the current user is a Treasury entity.
 * Useful for actions that only treasury centers can perform.
 *
 * @example
 * <TreasuryOnly>
 *   <Button onClick={handleApprove}>Approve Recharge</Button>
 * </TreasuryOnly>
 */
export const TreasuryOnly: React.FC<TreasuryOnlyProps> = ({
  children,
  fallback = null
}) => {
  const { isTreasury } = usePermissions();

  if (isTreasury) {
    return <>{children}</>;
  }

  return <>{fallback}</>;
};

interface SubsidiaryOnlyProps {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

/**
 * Renders children only if the current user is a Subsidiary entity.
 * Useful for actions specific to subsidiary entities.
 *
 * @example
 * <SubsidiaryOnly>
 *   <Button onClick={handleRequestRecharge}>Request Recharge</Button>
 * </SubsidiaryOnly>
 */
export const SubsidiaryOnly: React.FC<SubsidiaryOnlyProps> = ({
  children,
  fallback = null
}) => {
  const { isSubsidiary } = usePermissions();

  if (isSubsidiary) {
    return <>{children}</>;
  }

  return <>{fallback}</>;
};

/**
 * Hook to check if current user has a specific permission.
 * Use when you need conditional logic instead of rendering.
 *
 * @example
 * const canApprove = useHasPermission('canApproveNettingCycle');
 * if (canApprove) {
 *   // do something
 * }
 */
export const useHasPermission = (permission: keyof ReturnType<typeof usePermissions>): boolean => {
  const permissions = usePermissions();
  return !!permissions[permission];
};

export default PermissionGate;
