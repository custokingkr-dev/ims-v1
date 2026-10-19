export type Role = 'SUPERADMIN' | 'ZONE_ADMIN' | 'ADMIN' | 'SCHOOL_ADMIN' | 'OPERATIONS' | 'ACCOUNTANT' | 'TEACHER' | 'VIEWER';

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
  operatorSchools?: number[];
  /** Item-specific operator scopes as `schoolId:CATEGORY`; operatorSchools lists school-wide assignments only. */
  operatorScopes?: string[];
  sessionId?: string | null;
  stepUpExpiresAt?: string | null;
}
