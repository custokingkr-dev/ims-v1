import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { usePermissions } from './usePermissions';

/**
 * Tests for usePermissions hook.
 *
 * The hook delegates to useAuth() which reads from AuthContext.
 * We mock the AuthContext module so we can control the user's permission list
 * without needing a real AuthProvider or HTTP server.
 */

// Mock the AuthContext module — replaces the real import in usePermissions.ts
vi.mock('../contexts/AuthContext', () => ({
  useAuth: vi.fn(),
}));

// Import the mock after vi.mock so we can configure its return value per test.
import { useAuth } from '../contexts/AuthContext';
const mockUseAuth = vi.mocked(useAuth);

// Helper: render the hook with a specific permission set
function renderWithPermissions(permissions: string[]) {
  mockUseAuth.mockReturnValue({
    user: {
      accessToken: 'token',
      userId: 1,
      email: 'test@test.com',
      fullName: 'Test',
      role: 'ADMIN' as const,
      permissions,
    },
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
  return renderHook(() => usePermissions());
}

// Helper: render the hook with no user logged in
function renderWithNoUser() {
  mockUseAuth.mockReturnValue({
    user: null,
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
  return renderHook(() => usePermissions());
}

describe('usePermissions', () => {

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── can() ─────────────────────────────────────────────────────────────────

  describe('can()', () => {
    it('returns true when the permission is in the user list', () => {
      const { result } = renderWithPermissions(['student:read', 'fee:collect']);
      expect(result.current.can('student:read')).toBe(true);
    });

    it('returns false when the permission is NOT in the user list', () => {
      const { result } = renderWithPermissions(['student:read']);
      expect(result.current.can('student:create')).toBe(false);
    });

    it('returns false when the user has no permissions at all', () => {
      const { result } = renderWithPermissions([]);
      expect(result.current.can('student:read')).toBe(false);
    });

    it('returns false when there is no logged-in user', () => {
      const { result } = renderWithNoUser();
      expect(result.current.can('student:read')).toBe(false);
    });

    it('is case-sensitive — mixed case is not matched', () => {
      const { result } = renderWithPermissions(['student:read']);
      expect(result.current.can('Student:Read')).toBe(false);
    });

    it('does not match permission substrings', () => {
      const { result } = renderWithPermissions(['fee:read']);
      expect(result.current.can('fee')).toBe(false);
      expect(result.current.can(':read')).toBe(false);
    });
  });

  // ── canAny() ──────────────────────────────────────────────────────────────

  describe('canAny()', () => {
    it('returns true when the user has at least one of the given codes', () => {
      const { result } = renderWithPermissions(['fee:collect', 'audit:read']);
      expect(result.current.canAny(['fee:collect', 'student:read'])).toBe(true);
    });

    it('returns false when the user has none of the given codes', () => {
      const { result } = renderWithPermissions(['audit:read']);
      expect(result.current.canAny(['fee:collect', 'student:read'])).toBe(false);
    });

    it('returns false for an empty codes array', () => {
      const { result } = renderWithPermissions(['student:read']);
      expect(result.current.canAny([])).toBe(false);
    });

    it('returns false when there is no logged-in user', () => {
      const { result } = renderWithNoUser();
      expect(result.current.canAny(['student:read', 'fee:collect'])).toBe(false);
    });

    it('returns true when all given codes are held (superset match)', () => {
      const { result } = renderWithPermissions(['student:read', 'fee:collect', 'audit:read']);
      expect(result.current.canAny(['student:read', 'fee:collect'])).toBe(true);
    });
  });

  // ── canAll() ──────────────────────────────────────────────────────────────

  describe('canAll()', () => {
    it('returns true when the user holds every code in the list', () => {
      const { result } = renderWithPermissions(['student:read', 'fee:collect']);
      expect(result.current.canAll(['student:read', 'fee:collect'])).toBe(true);
    });

    it('returns false when the user is missing at least one code', () => {
      const { result } = renderWithPermissions(['student:read']);
      expect(result.current.canAll(['student:read', 'fee:collect'])).toBe(false);
    });

    it('returns true for an empty codes array (vacuous truth)', () => {
      const { result } = renderWithPermissions([]);
      // every() on empty array returns true
      expect(result.current.canAll([])).toBe(true);
    });

    it('returns false when there is no logged-in user and codes are non-empty', () => {
      const { result } = renderWithNoUser();
      expect(result.current.canAll(['student:read'])).toBe(false);
    });

    it('returns true for empty codes even with no logged-in user', () => {
      const { result } = renderWithNoUser();
      expect(result.current.canAll([])).toBe(true);
    });
  });

  // ── Combined scenarios ────────────────────────────────────────────────────

  describe('real-world permission combinations', () => {
    it('an accountant can read fees and collect but not manage students', () => {
      const { result } = renderWithPermissions([
        'fee:read', 'fee:collect', 'payment:read', 'invoice:read',
      ]);
      expect(result.current.can('fee:read')).toBe(true);
      expect(result.current.can('fee:collect')).toBe(true);
      expect(result.current.can('student:create')).toBe(false);
      expect(result.current.canAny(['fee:collect', 'student:create'])).toBe(true);
      expect(result.current.canAll(['fee:read', 'student:create'])).toBe(false);
    });

    it('a viewer can only read, not mutate', () => {
      const { result } = renderWithPermissions([
        'student:read', 'fee:read', 'attendance:read',
      ]);
      expect(result.current.can('student:read')).toBe(true);
      expect(result.current.can('student:create')).toBe(false);
      expect(result.current.canAll(['student:read', 'fee:read', 'attendance:read'])).toBe(true);
      expect(result.current.canAll(['student:read', 'student:create'])).toBe(false);
    });

    it('a superadmin with all permissions passes every check', () => {
      const allPerms = [
        'platform:admin', 'school:read', 'school:create', 'student:read',
        'student:create', 'student:update', 'student:delete', 'fee:read',
        'fee:collect', 'fee:reverse', 'order:read', 'order:approve',
        'firefighting:read', 'firefighting:approve', 'audit:read',
      ];
      const { result } = renderWithPermissions(allPerms);
      expect(result.current.canAll(['platform:admin', 'audit:read'])).toBe(true);
      expect(result.current.canAny(['some:unknown', 'fee:collect'])).toBe(true);
    });
  });
});
