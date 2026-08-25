import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createIdentityAuthClient, type AuthResponse } from './identityAuthApi';

const principal: AuthResponse = {
  accessToken: 'access-token',
  userId: 17,
  fullName: 'API Contract Fixture',
  email: 'fixture@example.test',
  role: 'SCHOOL_ADMIN',
  branchId: 41,
  branchName: 'Contract School',
  zoneId: null,
  zoneName: null,
  roles: ['SCHOOL_ADMIN'],
  permissions: ['student:read'],
  operatorSchools: [],
};

describe('generated identity authentication client', () => {
  it('uses canonical gateway paths and typed request/response bodies', async () => {
    const post = vi.fn()
      .mockResolvedValueOnce({ data: principal })
      .mockResolvedValueOnce({ data: principal })
      .mockResolvedValueOnce({ data: undefined });
    const client = createIdentityAuthClient({ post } as unknown as AxiosInstance);

    await expect(client.login({
      email: 'fixture@example.test',
      password: 'not-a-real-password',
    })).resolves.toEqual(principal);
    await expect(client.refresh()).resolves.toEqual(principal);
    await expect(client.logout()).resolves.toBeUndefined();

    expect(post).toHaveBeenNthCalledWith(1, '/auth/login', {
      email: 'fixture@example.test',
      password: 'not-a-real-password',
    });
    expect(post).toHaveBeenNthCalledWith(2, '/auth/refresh');
    expect(post).toHaveBeenNthCalledWith(3, '/auth/logout');
  });
});
