import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ResetPasswordPage from './ResetPasswordPage';

const mocks = vi.hoisted(() => ({ passwordResetCapabilities: vi.fn(), requestPasswordReset: vi.fn(), confirmPasswordReset: vi.fn(), setAccessToken: vi.fn() }));
vi.mock('../services/api', () => ({ identityAuthClient: mocks, setAccessToken: mocks.setAccessToken }));
function mount() { return render(<MemoryRouter><ResetPasswordPage /></MemoryRouter>); }
describe('password recovery', () => {
  beforeEach(() => {
    vi.resetAllMocks(); window.history.replaceState(null, '', '/reset-password');
    mocks.passwordResetCapabilities.mockResolvedValue({ enabled: true });
  });
  afterEach(cleanup);

  it('offers administrator recovery when delivery is unavailable', async () => {
    mocks.passwordResetCapabilities.mockResolvedValue({ enabled: false }); mount();
    expect(await screen.findByText(/Password reset by email is not available/)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Send reset link' })).toBeNull();
  });
  it('keeps email for retry and handles shared request limits without claiming an email was sent', async () => {
    mocks.requestPasswordReset.mockRejectedValueOnce({ response: { status: 429, headers: { 'retry-after': '3600' } } })
      .mockResolvedValueOnce({ message: 'If the account is eligible, a reset link will be sent.' });
    mount(); await screen.findByLabelText('Email address');
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'staff@example.invalid' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('60 minutes');
    expect(screen.getByLabelText('Email address')).toHaveValue('staff@example.invalid');
    fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }));
    expect(await screen.findByRole('status')).toHaveTextContent('If the account is eligible');
  });
  it('removes the reset secret from the URL and only submits matching valid passwords', async () => {
    const token = 'A'.repeat(43); window.history.replaceState(null, '', `/reset-password#token=${token}`);
    mocks.confirmPasswordReset.mockResolvedValue(undefined); mount();
    expect(window.location.hash).toBe(''); await screen.findByLabelText('New password');
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'replacement-password' } });
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'different-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('do not match');
    expect(mocks.confirmPasswordReset).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'replacement-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByRole('heading', { name: 'Password changed' })).toBeVisible();
    expect(mocks.confirmPasswordReset).toHaveBeenCalledWith({ token, password: 'replacement-password' });
    expect(mocks.setAccessToken).toHaveBeenCalledWith(null);
  });
  it('offers a new link when confirmation expires', async () => {
    window.history.replaceState(null, '', `/reset-password#token=${'B'.repeat(43)}`);
    mocks.confirmPasswordReset.mockRejectedValue({ response: { status: 400 } }); mount();
    await screen.findByLabelText('New password');
    for (const label of ['New password', 'Confirm new password']) fireEvent.change(screen.getByLabelText(label), { target: { value: 'replacement-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('invalid or expired');
    fireEvent.click(screen.getByRole('button', { name: 'Request a new link' }));
    await waitFor(() => expect(screen.getByLabelText('Email address')).toBeVisible());
  });
});
