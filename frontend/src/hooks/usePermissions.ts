import { useAuth } from '../contexts/AuthContext';

/**
 * Returns permission-checking helpers derived from the current user's
 * effective permission list (loaded from the backend at login time).
 *
 * Usage:
 *   const { can, canAny, canAll } = usePermissions();
 *   if (can('student:create')) { ... }
 *   if (canAny(['fee:collect', 'fee:read'])) { ... }
 */
export function usePermissions() {
  const { user } = useAuth();
  const permissions = user?.permissions ?? [];

  function can(code: string): boolean {
    return permissions.includes(code);
  }

  function canAny(codes: string[]): boolean {
    return codes.some(c => permissions.includes(c));
  }

  function canAll(codes: string[]): boolean {
    return codes.every(c => permissions.includes(c));
  }

  return { can, canAny, canAll };
}
