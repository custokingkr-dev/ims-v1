import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const axiosFixture = vi.hoisted(() => ({
  post: vi.fn(),
  get: vi.fn(),
  requestInterceptorUse: vi.fn(() => 0),
  responseInterceptorUse: vi.fn(() => 0),
  createConfig: null as unknown,
}));

/**
 * Tests for api.ts token management and interceptor wiring.
 *
 * The 401-retry interceptor requires a full HTTP mock (msw or axios-mock-adapter)
 * to test end-to-end. Those tests are tracked as a follow-up (Group C).
 * Here we test the exported token utilities and module contract.
 *
 * See: docs/runbook.md — "Authentication issues" for prod troubleshooting.
 */

// We need to mock axios BEFORE importing api.ts so the axios.create() call
// inside api.ts picks up the mock. Use a factory function with vi.mock.
vi.mock('axios', async () => {
  // Minimal stub: create() returns an object with the methods we use.
  const instance = {
    interceptors: {
      request:  { use: axiosFixture.requestInterceptorUse },
      response: { use: axiosFixture.responseInterceptorUse },
    },
    post: axiosFixture.post,
    get: axiosFixture.get,
  };
  return {
    default: {
      create: vi.fn((config: unknown) => {
        axiosFixture.createConfig = config;
        return instance;
      }),
      isAxiosError: vi.fn(() => false),
    },
    // Named export used by api.ts type annotations
    AxiosError: class AxiosError extends Error {},
  };
});

// Import AFTER mocking so the module initialises with the mock axios.
import { getAccessToken, identityAuthClient, refreshToken, setAccessToken } from './api';

const authPrincipal = {
  accessToken: 'refreshed-access-token',
  userId: 17,
  fullName: 'Refresh Fixture',
  email: 'refresh@example.test',
  role: 'SCHOOL_ADMIN' as const,
  branchId: 41,
  branchName: 'Contract School',
  zoneId: null,
  zoneName: null,
  roles: ['SCHOOL_ADMIN'],
  permissions: ['student:read'],
  operatorSchools: [],
};

describe('api.ts token utilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    axiosFixture.post.mockReset();
    localStorage.clear();
  });

  afterEach(() => {
    // Reset token state between tests
    setAccessToken(null);
  });

  describe('setAccessToken', () => {
    it('accepts a string token without throwing', () => {
      expect(() => setAccessToken('eyJhbGciOiJIUzI1NiJ9.test.sig')).not.toThrow();
    });

    it('accepts null to clear the token without throwing', () => {
      setAccessToken('some-token');
      expect(() => setAccessToken(null)).not.toThrow();
    });

    it('can be called multiple times', () => {
      setAccessToken('token-1');
      setAccessToken('token-2');
      setAccessToken(null);
      expect(() => setAccessToken('token-3')).not.toThrow();
    });
  });
});

/**
 * Module shape assertion — verify that the api module exports the expected
 * public functions so consumers won't hit runtime "not a function" errors.
 *
 * Note: the full 401-retry interceptor behaviour is an integration-level test
 * that requires msw or axios-mock-adapter. Tracked for Group C.
 */
describe('api.ts module exports', () => {
  it('exports setAccessToken as a function', async () => {
    const mod = await import('./api');
    expect(typeof mod.setAccessToken).toBe('function');
  });

  it('keeps streaming-download token access in the in-memory API module', async () => {
    const mod = await import('./api');
    mod.setAccessToken('stream-token');
    expect(mod.getAccessToken()).toBe('stream-token');
    mod.setAccessToken(null);
  });

  it('exports refreshToken as a function', async () => {
    const mod = await import('./api');
    expect(typeof mod.refreshToken).toBe('function');
  });

  it('exports the generated identity client bound to the shared axios instance', () => {
    expect(typeof identityAuthClient.login).toBe('function');
    expect(typeof identityAuthClient.refresh).toBe('function');
    expect(typeof identityAuthClient.logout).toBe('function');
  });

  it('retains credentialed cookie transport for generated refresh and logout operations', () => {
    expect(axiosFixture.createConfig).toEqual(expect.objectContaining({
      timeout: 30000,
      withCredentials: true,
    }));
  });

  it('exports a default axios instance', async () => {
    const mod = await import('./api');
    expect(mod.default).toBeDefined();
  });
});

describe('generated identity refresh migration', () => {
  it('uses the canonical generated refresh operation and preserves in-memory token semantics', async () => {
    axiosFixture.post.mockResolvedValueOnce({ data: authPrincipal });

    await expect(refreshToken()).resolves.toEqual(authPrincipal);

    expect(axiosFixture.post).toHaveBeenCalledOnce();
    expect(axiosFixture.post).toHaveBeenCalledWith('/auth/refresh');
    expect(getAccessToken()).toBe('refreshed-access-token');
  });

  it('deduplicates concurrent refreshes through the generated client', async () => {
    let resolveRefresh!: (value: { data: typeof authPrincipal }) => void;
    axiosFixture.post.mockReturnValueOnce(new Promise((resolve) => { resolveRefresh = resolve; }));
    const callsBeforeRefresh = axiosFixture.post.mock.calls.length;

    const first = refreshToken();
    const second = refreshToken();
    const callsAfterAdmission = axiosFixture.post.mock.calls.length;

    resolveRefresh({ data: authPrincipal });
    await expect(Promise.all([first, second])).resolves.toEqual([authPrincipal, authPrincipal]);
    expect(callsAfterAdmission - callsBeforeRefresh).toBe(1);
  });

  it('retains refresh failure cleanup for token and session markers', async () => {
    setAccessToken('expired-access-token');
    localStorage.setItem('custoking_isLoggedIn', 'true');
    axiosFixture.post.mockRejectedValueOnce(new Error('refresh rejected'));

    await expect(refreshToken()).resolves.toBeNull();

    expect(getAccessToken()).toBeNull();
    expect(localStorage.getItem('custoking_isLoggedIn')).toBeNull();
  });
});
