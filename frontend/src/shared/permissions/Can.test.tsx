import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Can } from './Can';

/**
 * Tests for the <Can> declarative permission-gate component.
 *
 * The component depends on usePermissions → useAuth → AuthContext.
 * We mock the AuthContext module to control the permission set per test.
 */

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: vi.fn(),
}));

import { useAuth } from '../../contexts/AuthContext';
const mockUseAuth = vi.mocked(useAuth);

// ── helpers ───────────────────────────────────────────────────────────────────

function withPermissions(permissions: string[]) {
  mockUseAuth.mockReturnValue({
    user: {
      accessToken: 'tok',
      userId: 42,
      email: 'u@test.com',
      fullName: 'Test User',
      role: 'ADMIN' as const,
      permissions,
    },
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
}

function withNoUser() {
  mockUseAuth.mockReturnValue({
    user: null,
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
}

// ── tests ─────────────────────────────────────────────────────────────────────

describe('<Can> permission gate', () => {

  beforeEach(() => vi.clearAllMocks());

  // ── single permission prop ────────────────────────────────────────────────

  describe('permission prop (single code)', () => {
    it('renders children when the user holds the permission', () => {
      withPermissions(['student:read']);
      render(<Can permission="student:read"><span>visible</span></Can>);
      expect(screen.getByText('visible')).toBeInTheDocument();
    });

    it('renders nothing when the user lacks the permission', () => {
      withPermissions(['fee:read']);
      render(<Can permission="student:read"><span>hidden</span></Can>);
      expect(screen.queryByText('hidden')).not.toBeInTheDocument();
    });

    it('renders fallback when permission is denied', () => {
      withPermissions([]);
      render(
        <Can permission="student:create" fallback={<span>no-access</span>}>
          <span>secret</span>
        </Can>,
      );
      expect(screen.getByText('no-access')).toBeInTheDocument();
      expect(screen.queryByText('secret')).not.toBeInTheDocument();
    });

    it('renders nothing (not fallback) when no fallback prop given', () => {
      withPermissions([]);
      const { container } = render(<Can permission="student:read"><span>x</span></Can>);
      expect(container).toBeEmptyDOMElement();
    });

    it('renders children when user is unauthenticated but has the code (edge case)', () => {
      withNoUser();
      render(<Can permission="student:read"><span>blocked</span></Can>);
      expect(screen.queryByText('blocked')).not.toBeInTheDocument();
    });
  });

  // ── anyOf prop (OR logic) ─────────────────────────────────────────────────

  describe('anyOf prop (OR logic)', () => {
    it('renders children when at least one code matches', () => {
      withPermissions(['fee:collect']);
      render(
        <Can anyOf={['fee:collect', 'student:create']}><span>anyof-content-shown</span></Can>,
      );
      expect(screen.getByText('anyof-content-shown')).toBeInTheDocument();
    });

    it('renders fallback when no codes match', () => {
      withPermissions(['audit:read']);
      render(
        <Can anyOf={['fee:collect', 'student:create']} fallback={<span>anyof-fallback-shown</span>}>
          <span>anyof-content-hidden</span>
        </Can>,
      );
      expect(screen.getByText('anyof-fallback-shown')).toBeInTheDocument();
      expect(screen.queryByText('anyof-content-hidden')).not.toBeInTheDocument();
    });
  });

  // ── allOf prop (AND logic) ────────────────────────────────────────────────

  describe('allOf prop (AND logic)', () => {
    it('renders children when all codes are held', () => {
      withPermissions(['order:approve', 'order:fulfill']);
      render(
        <Can allOf={['order:approve', 'order:fulfill']}><span>allof-content-shown</span></Can>,
      );
      expect(screen.getByText('allof-content-shown')).toBeInTheDocument();
    });

    it('renders fallback when even one code is missing', () => {
      withPermissions(['order:approve']);
      render(
        <Can allOf={['order:approve', 'order:fulfill']} fallback={<span>allof-fallback-shown</span>}>
          <span>allof-content-hidden</span>
        </Can>,
      );
      expect(screen.getByText('allof-fallback-shown')).toBeInTheDocument();
      expect(screen.queryByText('allof-content-hidden')).not.toBeInTheDocument();
    });
  });

  // ── no permission prop (developer guard) ─────────────────────────────────

  describe('no permission prop', () => {
    it('renders children unconditionally (developer convenience)', () => {
      withPermissions([]);
      render(<Can><span>always</span></Can>);
      expect(screen.getByText('always')).toBeInTheDocument();
    });
  });
});
