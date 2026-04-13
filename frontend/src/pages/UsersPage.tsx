import { useEffect, useState } from 'react';
import api from '../services/api';

export default function UsersPage() {
  const [users, setUsers] = useState<any[]>([]);
  useEffect(() => { api.get('/users').then((res) => setUsers(res.data)); }, []);

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Access control</div>
            <h1 className="page-title">Platform <em>users</em></h1>
            <p className="page-subtitle">Review active users, their roles, and branch coverage from one central view.</p>
          </div>
          <div className="badge">{users.length} users</div>
        </div>
      </section>
      <div className="card">
        <div className="section-head"><div><h2>Users</h2><p className="section-copy">Role-aligned access across the platform.</p></div></div>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Branch</th></tr></thead>
            <tbody>{users.map(u => <tr key={u.id}><td>{u.fullName}</td><td>{u.email}</td><td><span className="badge">{u.role}</span></td><td>{u.branchName || 'Global'}</td></tr>)}</tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
