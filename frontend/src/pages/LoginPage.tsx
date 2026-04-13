import { FormEvent, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

const ROLE_PRESETS = {
  admin: {
    email: '',
    password: '',
    icon: '🏫',
    title: 'Admin',
    description: 'School management',
    context: 'Manage school orders, student lists, uniform tracking and invoices.'
  },
  superadmin: {
    email: 'superadmin@custoking.com',
    password: 'Admin@123',
    icon: '🛡️',
    title: 'Super Admin',
    description: 'Platform control',
    context: 'Full platform control — users, schools, billing, inventory and analytics.'
  }
} as const;

type RoleKey = keyof typeof ROLE_PRESETS;

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [role, setRole] = useState<RoleKey>('superadmin');
  const [email, setEmail] = useState<string>(ROLE_PRESETS.superadmin.email);
  const [password, setPassword] = useState<string>(ROLE_PRESETS.superadmin.password);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const current = useMemo(() => ROLE_PRESETS[role], [role]);

  function switchRole(next: RoleKey) {
    setRole(next);
    setEmail(ROLE_PRESETS[next].email);
    setPassword(ROLE_PRESETS[next].password);
    setError('');
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    try {
      setLoading(true);
      setError('');
      await login(email, password);
      navigate('/dashboard');
    } catch {
      setError('Login failed. Please verify the selected role and credentials.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={`centered-card ${role === 'superadmin' ? 'superadmin-theme' : 'admin-theme'}`}>
      <form className="login-shell" onSubmit={onSubmit}>
        <div className="login-body">
          <div className="brand-row">
            <div className="brand">
              <div className="gem">🎒</div>
              <div>
                <div className="brand-name">Custo<span>king</span></div>
                <div className="brand-sub">IMS</div>
              </div>
            </div>
            <div className="env-badge"><span style={{ color: '#22C55E' }}>●</span> Production</div>
          </div>

          <div>
            <h1 className="page-title">Sign in to <em>Custoking</em></h1>
            <p className="page-subtitle">Access your dashboard, orders, inventory, approvals and billing from one place.</p>
          </div>

          <div style={{ marginTop: 26 }}>
            <div className="section-label">Account type</div>
            <div className="role-switch">
              {(Object.entries(ROLE_PRESETS) as [RoleKey, typeof ROLE_PRESETS[RoleKey]][]).map(([key, preset]) => (
                <button type="button" key={key} className={`role-button${role === key ? ' active' : ''}`} onClick={() => switchRole(key)}>
                  <div className="role-icon">{preset.icon}</div>
                  <div className="role-copy">
                    <strong>{preset.title}</strong>
                    <span>{preset.description}</span>
                  </div>
                </button>
              ))}
            </div>
            <div className="role-context">{current.context}</div>
          </div>

          <div className="field-grid">
            <div>
              <div className="field-label-row"><span>Email address</span></div>
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@custoking.com" />
            </div>
            <div>
              <div className="field-label-row"><span>Password</span><a href="#" onClick={(e) => e.preventDefault()}>Forgot password?</a></div>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Enter your password" />
            </div>
            <button type="submit" disabled={loading}>{loading ? 'Signing in…' : 'Sign in →'}</button>
            {error && <p className="error">{error}</p>}
          </div>

        </div>
        <div className="login-footer">
          <div>Need access? Contact your platform administrator.</div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <span>🔒 SSL</span>
            <span>🇮🇳 India</span>
            <span>✅ ISO 27001</span>
          </div>
        </div>
      </form>
    </div>
  );
}
