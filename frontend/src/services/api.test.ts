import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

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
  const interceptorSpy = { use: vi.fn(() => 0) };
  const instance = {
    interceptors: {
      request:  { use: interceptorSpy.use },
      response: { use: interceptorSpy.use },
    },
    post: vi.fn(),
    get:  vi.fn(),
  };
  return {
    default: {
      create: vi.fn(() => instance),
      isAxiosError: vi.fn(() => false),
    },
    // Named export used by api.ts type annotations
    AxiosError: class AxiosError extends Error {},
  };
});

// Import AFTER mocking so the module initialises with the mock axios.
import { setAccessToken } from './api';

describe('api.ts token utilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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

  it('exports refreshToken as a function', async () => {
    const mod = await import('./api');
    expect(typeof mod.refreshToken).toBe('function');
  });

  it('exports a default axios instance', async () => {
    const mod = await import('./api');
    expect(mod.default).toBeDefined();
  });
});
