import { FormEvent, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

// ── Strings — single extraction point for future i18n ─────────────────────────
const S = {
  brand: 'Custoking',
  brandSub: 'School Operations Platform',
  heading: 'Sign in',
  subtitle: 'Access your school operations, orders, and approvals.',
  statusNormal: 'All systems normal',
  googleLabel: 'Google',
  msLabel: 'Microsoft',
  divider: 'OR USE EMAIL',
  emailLabel: 'Email address',
  emailPlaceholder: 'you@yourcompany.com',
  passwordLabel: 'Password',
  forgotPassword: 'Forgot password?',
  showPassword: 'Show password',
  hidePassword: 'Hide password',
  capsLock: 'Caps Lock is on.',
  submit: 'Sign in',
  submitting: 'Signing in…',
  footerMfa: "You'll be asked to verify with your authenticator next.",
  footerNeedAccess: 'Need access?',
  footerContact: 'Contact your IT admin',
  copyright: '© Custoking',
  terms: 'Terms',
  privacy: 'Privacy',
  security: 'Security',
  errEmailEmpty: 'Email is required.',
  errEmailInvalid: 'Enter a valid email address.',
  errPasswordEmpty: 'Password is required.',
  errAuthFailed: "We couldn't sign you in. Check your email and password and try again.",
  errNetwork: 'Something went wrong on our end. Please try again in a moment.',
  errSsoStub:
    'Single sign-on is not configured for this build yet. Please use email and password, or contact your IT admin.',
};

// ── Inline icons (stroke-based, sized via width/height, color via currentColor) ─

function CrownIcon() {
  return (
    <svg width="20" height="18" viewBox="0 0 24 22" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 19h20M4 8l4 7 4-11 4 11 4-7v10H4V8z" />
    </svg>
  );
}

function GoogleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#4285F4"
        d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
      <path fill="#34A853"
        d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
      <path fill="#FBBC05"
        d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
      <path fill="#EA4335"
        d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
    </svg>
  );
}

function MicrosoftIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 21 21" aria-hidden="true">
      <rect x="0"  y="0"  width="10" height="10" fill="#F25022" />
      <rect x="11" y="0"  width="10" height="10" fill="#7FBA00" />
      <rect x="0"  y="11" width="10" height="10" fill="#00A4EF" />
      <rect x="11" y="11" width="10" height="10" fill="#FFB900" />
    </svg>
  );
}

function EyeIcon({ open }: { open: boolean }) {
  return open ? (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  ) : (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

function ArrowIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="12 5 19 12 12 19" />
    </svg>
  );
}

function SpinnerIcon() {
  return (
    <svg className="spinner" width="16" height="16" viewBox="0 0 24 24"
         fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3"
              strokeDasharray="56" strokeDashoffset="42" strokeLinecap="round" />
    </svg>
  );
}

// ── Validation ────────────────────────────────────────────────────────────────

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ── Component ─────────────────────────────────────────────────────────────────

type FieldErrors = { email?: string; password?: string };

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [capsLockOn, setCapsLockOn] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  // TODO(sso): When a provider is configured, replace stub with:
  // window.location.href = `/api/auth/sso/${provider}/start`
  // The server redirects to the IdP, handles the callback, and issues tokens.
  function handleSsoClick(_provider: 'google' | 'microsoft') {
    setFormError(S.errSsoStub);
  }

  function validate(): boolean {
    const errs: FieldErrors = {};
    if (!email.trim()) {
      errs.email = S.errEmailEmpty;
    } else if (!EMAIL_RE.test(email.trim())) {
      errs.email = S.errEmailInvalid;
    }
    if (!password) {
      errs.password = S.errPasswordEmpty;
    }
    setFieldErrors(errs);
    if (errs.email) { emailRef.current?.focus(); return false; }
    if (errs.password) { passwordRef.current?.focus(); return false; }
    return true;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!validate()) return;
    setLoading(true);
    try {
      await login(email.trim(), password);
      navigate('/dashboard');
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      setFormError(
        status === 401 || status === 403 ? S.errAuthFailed : S.errNetwork
      );
    } finally {
      setLoading(false);
    }
  }

  const submitDisabled = loading || !email.trim() || !password;

  const pwDescribedBy = [
    fieldErrors.password ? 'pw-err' : '',
    capsLockOn ? 'caps-hint' : '',
  ].filter(Boolean).join(' ') || undefined;

  return (
    <div className="centered-card">
      <div>
        <form className="login-shell" onSubmit={onSubmit} noValidate>
          <div className="login-body">

            {/* ── 1. Brand row ─────────────────────────────────────────── */}
            <div className="brand-row">
              <div className="brand">
                <div className="gem" aria-hidden="true">
                  <CrownIcon />
                </div>
                <div>
                  <div className="brand-name">Custo<span>king</span></div>
                  <div className="brand-sub">{S.brandSub}</div>
                </div>
              </div>
              {/* Status pill — static badge; no link (status page not yet live) */}
              <div className="env-badge">
                <span className="status-dot" aria-hidden="true" />
                {S.statusNormal}
              </div>
            </div>

            {/* ── 2. Heading ───────────────────────────────────────────── */}
            <div>
              <h1 className="auth-title">{S.heading}</h1>
              <p className="auth-subtitle">{S.subtitle}</p>
            </div>

            {/* ── 3. Email / Password / Trust / Submit / Forgot ────────── */}
            <div className="field-grid">

              {/* Email */}
              <div>
                <div className="field-label-row">
                  <label htmlFor="email-input" className="field-label">
                    {S.emailLabel}
                  </label>
                </div>
                <input
                  id="email-input"
                  ref={emailRef}
                  type="email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  placeholder={S.emailPlaceholder}
                  autoComplete="username"
                  inputMode="email"
                  spellCheck={false}
                  aria-invalid={fieldErrors.email ? true : undefined}
                  aria-describedby={fieldErrors.email ? 'email-err' : undefined}
                  disabled={loading}
                />
                {fieldErrors.email && (
                  <span id="email-err" className="field-error">{fieldErrors.email}</span>
                )}
              </div>

              {/* Password */}
              <div>
                <div className="field-label-row">
                  <label htmlFor="pw-input" className="field-label">
                    {S.passwordLabel}
                  </label>
                </div>
                <div className={`password-row${fieldErrors.password ? ' has-error' : ''}`}>
                  <input
                    id="pw-input"
                    ref={passwordRef}
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    placeholder="••••••••"
                    autoComplete="current-password"
                    aria-invalid={fieldErrors.password ? true : undefined}
                    aria-describedby={pwDescribedBy}
                    onKeyDown={e => setCapsLockOn(e.getModifierState('CapsLock'))}
                    onKeyUp={e => setCapsLockOn(e.getModifierState('CapsLock'))}
                    onBlur={() => setCapsLockOn(false)}
                    disabled={loading}
                  />
                  <button
                    type="button"
                    className="eye-toggle"
                    aria-label={showPassword ? S.hidePassword : S.showPassword}
                    aria-pressed={showPassword}
                    onClick={() => setShowPassword(v => !v)}
                  >
                    <EyeIcon open={showPassword} />
                  </button>
                </div>
                {fieldErrors.password && (
                  <span id="pw-err" className="field-error">{fieldErrors.password}</span>
                )}
                {capsLockOn && (
                  <span id="caps-hint" className="caps-hint">{S.capsLock}</span>
                )}
              </div>

              {/* Form-level error */}
              {formError && (
                <div role="alert" aria-live="polite" className="form-error">
                  {formError}
                </div>
              )}

              {/* Submit */}
              <button type="submit" className="submit-btn" disabled={submitDisabled}>
                {loading ? <SpinnerIcon /> : <ArrowIcon />}
                {loading ? S.submitting : S.submit}
              </button>

              {/* Forgot — not yet built; non-interactive text, not a dead link */}
              <div style={{ textAlign: 'center' }}>
                <span className="forgot-link" title="Coming soon" aria-disabled="true">
                  {S.forgotPassword}
                </span>
              </div>

            </div>

            {/* ── 4. Social sign-in ────────────────────────────────────── */}
            <div className="divider-or" aria-hidden="true">
              <span>{S.divider}</span>
            </div>
            <div className="sso-secondary-row">
              <button
                type="button"
                className="sso-secondary"
                onClick={() => handleSsoClick('google')}
                disabled={loading}
              >
                <GoogleIcon />
                {S.googleLabel}
              </button>
              <button
                type="button"
                className="sso-secondary"
                onClick={() => handleSsoClick('microsoft')}
                disabled={loading}
              >
                <MicrosoftIcon />
                {S.msLabel}
              </button>
            </div>

          </div>

          {/* ── 11. Footer ───────────────────────────────────────────────── */}
          <div className="login-footer">
            <span>{S.footerMfa}</span>
            <span>
              {S.footerNeedAccess}{' '}
              {/* TODO: make this mailto configurable per workspace via API */}
              <a
                href="mailto:it@yourcompany.com?subject=Custoking%20IMS%20access"
                className="footer-link"
              >
                <strong>{S.footerContact}</strong>
              </a>
            </span>
          </div>
        </form>

        {/* ── 12. Legal row — below the card ─────────────────────────────── */}
        {/* Terms / Privacy / Security pages not yet built; non-interactive text, not dead links */}
        <div className="legal-row">
          <span>{S.copyright}</span>
          <span title="Coming soon" aria-disabled="true">{S.terms}</span>
          <span title="Coming soon" aria-disabled="true">{S.privacy}</span>
          <span title="Coming soon" aria-disabled="true">{S.security}</span>
        </div>
      </div>
    </div>
  );
}
