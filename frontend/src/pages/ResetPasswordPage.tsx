import { FormEvent, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { identityAuthClient, setAccessToken } from '../services/api';

function resetError(error: unknown, confirming: boolean): string {
  const response = (error as { response?: { status?: number; headers?: Record<string, string> } })?.response;
  if (response?.status === 429) {
    const seconds = Number(response.headers?.['retry-after']);
    return `Too many attempts. Try again ${Number.isFinite(seconds) && seconds > 0 ? `in ${Math.ceil(seconds / 60)} minute${seconds > 60 ? 's' : ''}` : 'later'}.`;
  }
  if (response?.status === 503) return 'Password reset is unavailable. Contact your school or organization administrator.';
  if (confirming && response?.status === 400) return 'This reset link is invalid or expired. Request a new link.';
  return confirming
    ? 'We could not confirm the password change. Try signing in with your new password first. If it does not work, request a new link.'
    : 'We could not confirm your request. Check your connection and try again.';
}

export default function ResetPasswordPage() {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') || '');
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [capabilityError, setCapabilityError] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [changed, setChanged] = useState(false);
  const errorRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);

  useEffect(() => {
    // Preserve the secret only in component memory, never URLs, storage or analytics events.
    if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
  }, []);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);

  async function loadCapability() {
    setCapabilityError(false);
    try { setEnabled((await identityAuthClient.passwordResetCapabilities()).enabled); }
    catch { setCapabilityError(true); }
  }
  useEffect(() => { void loadCapability(); }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busyRef.current || !enabled) return;
    setError(''); setMessage('');
    if (token) {
      if ([...password].length < 12 || new TextEncoder().encode(password).length > 72) {
        setError('Use at least 12 characters. Your password must fit within 72 bytes; accented letters and emoji use more than one byte.'); return;
      }
      if (password !== confirmation) { setError('The passwords do not match. Re-enter the same password in both fields.'); return; }
    }
    busyRef.current = true; setBusy(true);
    try {
      if (token) {
        await identityAuthClient.confirmPasswordReset({ token, password });
        setAccessToken(null);
        try { localStorage.removeItem('custoking_isLoggedIn'); } catch { /* Server reset is already confirmed. */ }
        setToken(''); setPassword(''); setConfirmation(''); setChanged(true);
      } else {
        const result = await identityAuthClient.requestPasswordReset({ email: email.trim() });
        setMessage(result.message);
      }
    } catch (failure) { setError(resetError(failure, Boolean(token))); }
    finally { busyRef.current = false; setBusy(false); }
  }

  return <main className="centered-card"><div><div className="login-shell"><div className="login-body">
    <div><h1 className="auth-title">{changed ? 'Password changed' : token ? 'Choose a new password' : 'Reset your password'}</h1>
      <p className="auth-subtitle">{changed ? 'Your previous sessions have been signed out. Sign in with your new password.'
        : token ? 'Use a password you have not used for another account. Changing it signs out your existing sessions.'
        : 'Enter the email address for your Custoking account.'}</p></div>
    {capabilityError ? <div role="alert"><p>We could not check account recovery. Check your connection and try again.</p><button type="button" className="ck-btn ck-btn-ghost" onClick={() => void loadCapability()}>Try again</button></div>
      : enabled === null ? <p role="status">Checking account recovery…</p>
      : !enabled ? <p>Contact the school or organization administrator who gave you access. Password reset by email is not available right now.</p>
      : !changed && <form className="field-grid" onSubmit={submit}>
        {token ? <>
          <div><label className="field-label" htmlFor="new-password">New password</label><input id="new-password" type="password" autoComplete="new-password" required value={password} disabled={busy} onChange={event => setPassword(event.target.value)} aria-describedby="password-guidance" /></div>
          <p id="password-guidance">At least 12 characters. A short phrase is easier to remember.</p>
          <div><label className="field-label" htmlFor="confirm-password">Confirm new password</label><input id="confirm-password" type="password" autoComplete="new-password" required value={confirmation} disabled={busy} onChange={event => setConfirmation(event.target.value)} /></div>
        </> : <div><label className="field-label" htmlFor="reset-email">Email address</label><input id="reset-email" type="email" autoComplete="email" maxLength={254} required value={email} disabled={busy} onChange={event => setEmail(event.target.value)} /></div>}
        {error && <div ref={errorRef} tabIndex={-1} className="form-error" role="alert">{error}</div>}
        {message && <p role="status">{message} Links expire after 30 minutes. If no email arrives, try again later or contact your administrator.</p>}
        <button type="submit" className="submit-btn" disabled={busy}>{busy ? 'Please wait…' : token ? 'Change password' : 'Send reset link'}</button>
        {token && <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => { setToken(''); setPassword(''); setConfirmation(''); setError(''); }}>Request a new link</button>}
      </form>}
    <Link className="footer-link" to="/login">Back to sign in</Link>
  </div></div></div></main>;
}
