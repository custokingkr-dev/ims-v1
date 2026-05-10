export type Role = 'SUPERADMIN' | 'ZONE_ADMIN' | 'ADMIN' | 'OPERATIONS' | 'ACCOUNTANT' | 'TEACHER' | 'VIEWER';

export interface AuthUser {
  accessToken: string;
  userId: number;
  fullName: string;
  email: string;
  role: Role;
  branchId?: number | null;
  branchName?: string | null;
  zoneId?: number | null;
  zoneName?: string | null;
  roles?: string[];
  permissions?: string[];
}
