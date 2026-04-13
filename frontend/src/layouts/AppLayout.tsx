import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

const navItems = [
  { to: '/', label: 'Dashboard', icon: '◫' },
  { to: '/customers', label: 'Customers', icon: '◌' },
  { to: '/invoices', label: 'Invoices', icon: '▣' },
  { to: '/payments', label: 'Payments', icon: '₹' },
  { to: '/approvals', label: 'Approvals', icon: '✓' }
];

export default function AppLayout() {
  const { user, logout } = useAuth();
  const accentClass = user?.role === 'SUPERADMIN' ? 'superadmin-theme' : 'admin-theme';

  return (
    <div className={`app-shell ${accentClass}`}>
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-row" style={{ marginBottom: 14 }}>
            <div className="brand">
              <div className="gem">🎒</div>
              <div>
                <div className="brand-name">Custo<span>king</span></div>
                <div className="brand-sub">Order &amp; Inventory Platform</div>
              </div>
            </div>
            <div className="env-badge"><span style={{ color: '#22C55E' }}>●</span> Live</div>
          </div>
          <p className="section-copy" style={{ fontSize: 13, marginTop: 6 }}>
            Unified billing, approvals, customers, and school operations in one secure workspace.
          </p>
        </div>

        <nav>
          {navItems.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
              <span className="nav-ico">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
          {user?.role === 'SUPERADMIN' && (
            <>
              <NavLink to="/schools" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
                <span className="nav-ico">🏫</span>
                <span>Schools</span>
              </NavLink>
              <NavLink to="/users" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
                <span className="nav-ico">👥</span>
                <span>Users</span>
              </NavLink>
            </>
          )}
        </nav>

        <div className="spacer" />

        <div className="user-card">
          <div className="section-label">Signed in as</div>
          <h3 style={{ margin: '8px 0 4px' }}>{user?.fullName}</h3>
          <div className="top-note">{user?.email}</div>
          <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <span className="badge">{user?.role === 'SUPERADMIN' ? 'Super Admin' : 'Admin'}</span>
            <span className="badge">{user?.branchName || 'Global'}</span>
          </div>
        </div>

        <div className="logout-card">
          <button onClick={logout} style={{ width: '100%' }}>Logout</button>
          <p className="section-copy" style={{ fontSize: 12, marginTop: 10 }}>
            Need a different account? <Link to="/login" style={{ color: 'var(--c600)', fontWeight: 700 }}>Switch profile</Link>
          </p>
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
