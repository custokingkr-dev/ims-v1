export type Role = 'SUPERADMIN' | 'ADMIN';

export interface AuthUser {
  accessToken: string;
  refreshToken: string;
  userId: number;
  fullName: string;
  email: string;
  role: Role;
  branchId?: number | null;
  branchName?: string | null;
}
