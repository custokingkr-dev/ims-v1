/**
 * Permission-checking hook — promoted from hooks/usePermissions.ts.
 *
 * The original file at hooks/usePermissions.ts re-exports from here so
 * existing callers continue to work without any import changes.
 *
 * Usage:
 *   import { usePermissions } from 'shared/permissions/usePermissions';
 *   const { can, canAny, canAll } = usePermissions();
 *   if (can('student:create')) { ... }
 */
export { usePermissions } from '../../hooks/usePermissions';
