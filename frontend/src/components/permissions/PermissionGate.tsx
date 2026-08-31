import React from 'react';
import { usePermissions } from '../../hooks/usePermissions';
import { useUser } from '../../context/UserContext';

interface PermissionGateProps {
  children: React.ReactNode;
  /** Show only if user is Treasury */
  treasuryOnly?: boolean;
  /** Show only if user is Subsidiary */
  subsidiaryOnly?: boolean;
  /** Show only if user has specific permission */
  permission?: keyof ReturnType<typeof usePermissions>;
  /** Custom permission check function */
  check?: () => boolean;
  /** What to show if permission denied (default: nothing) */
  fallback?: React.ReactNode;
  /** Show disabled version instead of hiding */
  showDisabled?: boolean;
}

export const PermissionGate: React.FC<PermissionGateProps> = ({
  children,
  treasuryOnly,
  subsidiaryOnly,
  permission,
  check,
  fallback = null,
  showDisabled = false,
}) => {
  const { isTreasury, isSubsidiary } = useUser();
  const permissions = usePermissions();

  let hasPermission = true;

  if (treasuryOnly && !isTreasury) {
    hasPermission = false;
  }

  if (subsidiaryOnly && !isSubsidiary) {
    hasPermission = false;
  }

  if (permission && !permissions[permission]) {
    hasPermission = false;
  }

  if (check && !check()) {
    hasPermission = false;
  }

  if (!hasPermission) {
    if (showDisabled && React.isValidElement(children)) {
      return React.cloneElement(children as React.ReactElement<any>, {
        disabled: true,
        className: `${(children as React.ReactElement<any>).props.className || ''} opacity-50 cursor-not-allowed`,
      });
    }
    return <>{fallback}</>;
  }

  return <>{children}</>;
};

// Convenience components
export const TreasuryOnly: React.FC<{ children: React.ReactNode; fallback?: React.ReactNode }> = ({
  children,
  fallback
}) => (
  <PermissionGate treasuryOnly fallback={fallback}>
    {children}
  </PermissionGate>
);

export const SubsidiaryOnly: React.FC<{ children: React.ReactNode; fallback?: React.ReactNode }> = ({
  children,
  fallback
}) => (
  <PermissionGate subsidiaryOnly fallback={fallback}>
    {children}
  </PermissionGate>
);

export default PermissionGate;
