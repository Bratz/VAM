import { usePermissions } from '../../hooks/usePermissions';

/**
 * Hook to check if current user has a specific permission.
 * Use when you need conditional logic instead of rendering.
 *
 * Lives beside `index.tsx` rather than in it so that file can export the
 * permission-gate components alone and keep Fast Refresh working.
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
