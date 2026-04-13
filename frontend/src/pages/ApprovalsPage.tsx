import { useEffect, useState } from 'react';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';

export default function ApprovalsPage() {
  const { user } = useAuth();
  const [approvals, setApprovals] = useState<any[]>([]);
  const load = () => api.get('/approvals').then((res) => setApprovals(res.data));
  useEffect(() => {
    void load();
  }, []);

  async function decide(id: number, action: 'approve' | 'reject') {
    await api.post(`/approvals/${id}/${action}`, { decisionNote: `Reviewed by ${user?.fullName}` });
    void load();
  }

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Governance</div>
            <h1 className="page-title">Review pending <em>approvals</em></h1>
            <p className="page-subtitle">Approve or reject platform actions with a clear audit-friendly interface.</p>
          </div>
          <div className="badge">{approvals.filter((a) => a.status === 'PENDING').length} pending</div>
        </div>
      </section>

      <div className="card">
        <div className="section-head"><div><h2>Approvals queue</h2><p className="section-copy">Decision workflow for discount, cancellation, and exceptional billing actions.</p></div></div>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Invoice</th><th>Type</th><th>Status</th><th>Reason</th><th>Actions</th></tr></thead>
            <tbody>
              {approvals.map((a) => (
                <tr key={a.id}>
                  <td>{a.invoiceNo}</td>
                  <td>{a.requestType}</td>
                  <td><span className={`status-pill ${String(a.status).toLowerCase()}`}>{a.status}</span></td>
                  <td>{a.reason}</td>
                  <td>
                    {user?.role === 'SUPERADMIN' && a.status === 'PENDING' ? (
                      <div className="actions-inline">
                        <button type="button" onClick={() => decide(a.id, 'approve')}>Approve</button>
                        <button type="button" className="secondary" onClick={() => decide(a.id, 'reject')}>Reject</button>
                      </div>
                    ) : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
