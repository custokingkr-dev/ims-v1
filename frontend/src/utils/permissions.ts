import type { AuthUser, Role } from '../types/auth';

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

export function hasRole(user: AuthUser | null, role: Role): boolean {
  if (!user) return false;
  return user.role === role;
}

export function isSuperAdmin(user: AuthUser | null): boolean {
  return hasRole(user, 'SUPERADMIN');
}

export function isZoneAdmin(user: AuthUser | null): boolean {
  return hasRole(user, 'ZONE_ADMIN');
}

export function isSchoolLevel(user: AuthUser | null): boolean {
  if (!user) return false;
  const schoolLevelRoles: Role[] = ['ADMIN', 'OPERATIONS', 'ACCOUNTANT', 'TEACHER', 'VIEWER'];
  return schoolLevelRoles.includes(user.role);
}

export function canWrite(user: AuthUser | null): boolean {
  if (!user) return false;
  const writeRoles: Role[] = ['SUPERADMIN', 'ADMIN', 'OPERATIONS'];
  return writeRoles.includes(user.role);
}

export function canManageFees(user: AuthUser | null): boolean {
  if (!user) return false;
  const feeRoles: Role[] = ['SUPERADMIN', 'ADMIN', 'ACCOUNTANT'];
  return feeRoles.includes(user.role);
}
