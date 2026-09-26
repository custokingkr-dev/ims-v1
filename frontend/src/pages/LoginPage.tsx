import { FormEvent, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

// ── Strings — single extraction point for future i18n ─────────────────────────
const S = {
  brand: 'Custoking',
  brandSub: 'School Operations Platform',
  heading: 'Sign in',
  subtitle: 'Access your school operations, orders, and approvals.',
  emailLabel: 'Email address',
  emailPlaceholder: 'you@yourcompany.com',
  passwordLabel: 'Password',
  showPassword: 'Show password',
  hidePassword: 'Hide password',
  capsLock: 'Caps Lock is on.',
  submit: 'Sign in',
  submitting: 'Signing in…',
  copyright: '© Custoking',
  errEmailEmpty: 'Email is required.',
  errEmailInvalid: 'Enter a valid email address.',
  errPasswordEmpty: 'Password is required.',
  errAuthFailed: "We couldn't sign you in. Check your email and password and try again.",
  errNetwork: 'Sign-in could not be completed. Check your connection and try again. Your details are still here.',
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
    if (loading) return;
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

              <details style={{ fontSize: 14, color: 'var(--ink2)' }}>
                <summary style={{ cursor: 'pointer', textAlign: 'center' }}>Forgot your password or need access?</summary>
                <p>Contact the school or organization administrator who gave you access. Ask them to check your account email and help restore access.</p>
                <p><Link className="footer-link" to="/reset-password">Reset your password by email</Link></p>
              </details>
            </div>

          </div>

          {/* ── 11. Footer ───────────────────────────────────────────────── */}
          <div className="login-footer">
            <span>Use the email and password provided for your Custoking account.</span>
          </div>
        </form>

        <div className="legal-row"><span>{S.copyright}</span></div>
      </div>
    </div>
  );
}
