import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';

const { login, auth } = vi.hoisted(() => ({ login: vi.fn(), auth: { user: null as { id: number } | null } }));
vi.mock('../contexts/AuthContext', () => ({ useAuth: () => ({ login, user: auth.user }) }));

function DashboardWithBack() {
  const navigate = useNavigate();
  return <><p>Dashboard page</p><button onClick={() => navigate(-1)}>Back</button></>;
}

function renderAtLogin(initialEntries = ['/login'], initialIndex = initialEntries.length - 1) {
  return render(
    <MemoryRouter initialEntries={initialEntries} initialIndex={initialIndex}>
      <Routes>
        <Route path="/home" element={<p>Home page</p>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/dashboard" element={<DashboardWithBack />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('LoginPage supported sign-in and recovery', () => {
  beforeEach(() => { login.mockReset(); auth.user = null; });
  afterEach(cleanup);

  it('sends an already signed-in user to the dashboard instead of showing the sign-in form', () => {
    // A reload of /login silently restores the session from the refresh cookie. Leaving the form on
    // screen invited more reloads, and a reload during an in-flight refresh replays a rotated token.
    auth.user = { id: 1 };
    renderAtLogin();
    expect(screen.getByText('Dashboard page')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('replaces the sign-in page in history, so Back after signing in does not return to the form', async () => {
    login.mockResolvedValueOnce(undefined);
    renderAtLogin(['/home', '/login']);
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'staff@school.example' } });
    fireEvent.change(screen.getByLabelText('Password', { exact: true }), { target: { value: 'test-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Dashboard page')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByText('Home page')).toBeInTheDocument();
  });

  it('offers the configured password flow and administrator-assisted recovery without invented promises', () => {
    const { container } = render(<MemoryRouter><LoginPage /></MemoryRouter>);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Google|Microsoft/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/All systems normal|authenticator next/)).not.toBeInTheDocument();
    expect(container.querySelector('a[href^="mailto:"]')).toBeNull();
    fireEvent.click(screen.getByText('Forgot your password or need access?'));
    expect(screen.getByText(/administrator who gave you access/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Reset your password by email' })).toHaveAttribute('href', '/reset-password');
  });

  it('preserves credentials and permits retry after a failed sign-in', async () => {
    login.mockRejectedValueOnce({ response: { status: 401 } }).mockResolvedValueOnce(undefined);
    render(<MemoryRouter><LoginPage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'staff@school.example' } });
    fireEvent.change(screen.getByLabelText('Password', { exact: true }), { target: { value: 'test-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Check your email and password');
    expect(screen.getByLabelText('Email address')).toHaveValue('staff@school.example');
    expect(screen.getByLabelText('Password', { exact: true })).toHaveValue('test-password');
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(login).toHaveBeenCalledTimes(2));
  });
});
