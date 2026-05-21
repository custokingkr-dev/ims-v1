import type { AuthUser } from '../types/auth';

// ── Core permission helpers (permission-code based) ───────────────────────────

export function hasPermission(user: AuthUser | null, code: string): boolean {
  if (!user) return false;
  return user.permissions?.includes(code) ?? false;
}

export function hasAnyPermission(user: AuthUser | null, codes: string[]): boolean {
  if (!user || !codes.length) return false;
  return codes.some(c => hasPermission(user, c));
}

export function hasAllPermissions(user: AuthUser | null, codes: string[]): boolean {
  if (!user || !codes.length) return false;
  return codes.every(c => hasPermission(user, c));
}

// ── Convenience wrappers using permission codes (preferred) ───────────────────

export function canWrite(user: AuthUser | null): boolean {
  return hasAnyPermission(user, ['student:create', 'order:create', 'firefighting:create']);
}

export function canManageFees(user: AuthUser | null): boolean {
  return hasPermission(user, 'fee:collect');
}
