import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';

const authFixture = vi.hoisted(() => ({
  login: vi.fn(),
  logout: vi.fn(),
  refreshToken: vi.fn(),
  setAccessToken: vi.fn(),
}));

vi.mock('../services/api', () => ({
  identityAuthClient: {
    login: authFixture.login,
    logout: authFixture.logout,
  },
  refreshToken: authFixture.refreshToken,
  setAccessToken: authFixture.setAccessToken,
}));

const principal = {
  accessToken: 'login-access-token',
  userId: 23,
  fullName: 'Login Fixture',
  email: 'login@example.test',
  role: 'SCHOOL_ADMIN' as const,
  branchId: 51,
  branchName: 'Generated Client School',
  zoneId: null,
  zoneName: null,
  roles: ['SCHOOL_ADMIN'],
  permissions: ['workspace:access'],
  operatorSchools: [],
};

function AuthConsumer() {
  const auth = useAuth();
  return (
    <div>
      <span data-testid="user">{auth.user?.email ?? 'anonymous'}</span>
      <button type="button" onClick={() => void auth.login('login@example.test', 'password-fixture')}>
        Login
      </button>
      <button type="button" onClick={() => void auth.logout()}>
        Logout
      </button>
    </div>
  );
}

describe('AuthProvider generated identity client migration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authFixture.login.mockReset();
    authFixture.logout.mockReset();
    authFixture.refreshToken.mockReset();
    authFixture.setAccessToken.mockReset();
    localStorage.clear();
    authFixture.login.mockResolvedValue(principal);
    authFixture.logout.mockResolvedValue(undefined);
    authFixture.refreshToken.mockResolvedValue(null);
  });

  afterEach(() => cleanup());

  it('logs in through the generated client and retains token/session state behavior', async () => {
    render(<AuthProvider><AuthConsumer /></AuthProvider>);

    fireEvent.click(screen.getByRole('button', { name: 'Login' }));

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('login@example.test'));
    expect(authFixture.login).toHaveBeenCalledWith({
      email: 'login@example.test',
      password: 'password-fixture',
    });
    expect(authFixture.setAccessToken).toHaveBeenCalledWith('login-access-token');
    expect(localStorage.getItem('custoking_isLoggedIn')).toBe('true');
  });

  it('keeps logout best-effort and clears local state when the generated request fails', async () => {
    authFixture.logout.mockRejectedValueOnce(new Error('network unavailable'));
    render(<AuthProvider><AuthConsumer /></AuthProvider>);
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('login@example.test'));

    fireEvent.click(screen.getByRole('button', { name: 'Logout' }));

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('anonymous'));
    expect(authFixture.logout).toHaveBeenCalledOnce();
    expect(authFixture.setAccessToken).toHaveBeenLastCalledWith(null);
    expect(localStorage.getItem('custoking_isLoggedIn')).toBeNull();
  });
});
