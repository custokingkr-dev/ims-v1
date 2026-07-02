import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import api from '../services/api';
import { usePermissions } from '../hooks/usePermissions';
import { PageHero } from '../components/PageHero';

interface UserRecord {
  id: number;
  fullName: string;
  email: string;
  role: string;
  branchName?: string;
}

export default function UsersPage() {
  const { can } = usePermissions();
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get<UserRecord[]>('/users')
      .then((res) => setUsers(res.data))
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load users.';
        setError(msg);
      })
      .finally(() => setLoading(false));
  }, []);

  if (!can('user:read')) return <Navigate to="/dashboard" replace />;

  return (
    <div className="page-stack">
      <PageHero
        label="Access control"
        title={<>Platform <em>users</em></>}
        subtitle="Review active users, their roles, and branch coverage from one central view."
        actions={<div className="badge">{users.length} users</div>}
      />
      {error && <p className="ck-alert ck-alert-re">{error}</p>}
      <div className="card">
        <div className="section-head"><div><h2>Users</h2><p className="section-copy">Role-aligned access across the platform.</p></div></div>
        {loading ? <p>Loading…</p> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Branch</th></tr></thead>
              <tbody>
                {users.length === 0 && !loading && (
                  <tr><td colSpan={4} style={{ textAlign: 'center', color: '#6b7280', padding: '1rem' }}>No users found.</td></tr>
                )}
                {users.map(u => (
                  <tr key={u.id}>
                    <td>{u.fullName}</td>
                    <td>{u.email}</td>
                    <td><span className="badge">{u.role}</span></td>
                    <td>{u.branchName || 'Global'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
