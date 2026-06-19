import type { ReactNode } from 'react';
import { usePermissions } from '../../hooks/usePermissions';

interface CanProps {
  /**
   * Single permission code that must be held.
   *
   * Example: <Can permission="student:create">...</Can>
   */
  permission?: string;

  /**
   * Any one of these codes is sufficient (OR logic).
   *
   * Example: <Can anyOf={["fee:collect", "fee:reverse"]}>...</Can>
   */
  anyOf?: string[];

  /**
   * All of these codes must be held (AND logic).
   *
   * Example: <Can allOf={["order:approve", "order:fulfill"]}>...</Can>
   */
  allOf?: string[];

  /** Content to render when the permission check passes. */
  children: ReactNode;

  /**
   * Optional fallback rendered when the check fails.
   * Defaults to null (nothing rendered).
   */
  fallback?: ReactNode;
}

/**
 * Declarative permission gate component.
 *
 * Renders {@code children} when the current user holds the required
 * permission(s); renders {@code fallback} (default: null) otherwise.
 *
 * This is a **UI-visibility guard only**.  The backend enforces all
 * authorization — never treat this component as a security boundary.
 *
 * @example
 * // Single permission
 * <Can permission="student:create">
 *   <AddStudentButton />
 * </Can>
 *
 * @example
 * // Any of multiple permissions
 * <Can anyOf={["fee:report", "fee:collect"]} fallback={<p>No access</p>}>
 *   <FeeActions />
 * </Can>
 *
 * @example
 * // All permissions required
 * <Can allOf={["order:approve", "order:fulfill"]}>
 *   <ApproveAndFulfillButton />
 * </Can>
 */
export function Can({ permission, anyOf, allOf, children, fallback = null }: CanProps) {
  const { can, canAny, canAll } = usePermissions();

  let permitted = false;

  if (permission !== undefined) {
    permitted = can(permission);
  } else if (anyOf !== undefined) {
    permitted = canAny(anyOf);
  } else if (allOf !== undefined) {
    permitted = canAll(allOf);
  } else {
    // No permission specified — render children (developer error; warn in dev)
    if (import.meta.env.DEV) {
      console.warn('[Can] No permission prop specified — rendering children unconditionally.');
    }
    permitted = true;
  }

  return permitted ? <>{children}</> : <>{fallback}</>;
}
