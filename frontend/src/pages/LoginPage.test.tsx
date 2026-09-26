import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';

const { login } = vi.hoisted(() => ({ login: vi.fn() }));
vi.mock('../contexts/AuthContext', () => ({ useAuth: () => ({ login }) }));

describe('LoginPage supported sign-in and recovery', () => {
  beforeEach(() => login.mockReset());
  afterEach(cleanup);

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
