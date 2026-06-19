import { useEffect, useState } from 'react';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { PageHero } from '../components/PageHero';

interface ApprovalItem {
  id: number;
  invoiceNo: string;
  requestType: string;
  status: string;
  reason: string;
}

export default function ApprovalsPage() {
  const { user } = useAuth();
  const { can } = usePermissions();
  const [approvals, setApprovals] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [deciding, setDeciding] = useState<number | null>(null);

  const load = async () => {
    try {
      setLoading(true);
      setError('');
      const res = await api.get<ApprovalItem[]>('/approvals');
      setApprovals(res.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load approvals.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const decide = async (id: number, action: 'approve' | 'reject') => {
    try {
      setDeciding(id);
      setError('');
      await api.post(`/approvals/${id}/${action}`, { decisionNote: `Reviewed by ${user?.fullName}` });
      void load();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Action failed.';
      setError(msg);
    } finally {
      setDeciding(null);
    }
  };

  const pendingCount = approvals.filter((a) => a.status === 'PENDING').length;

  return (
    <div className="page-stack">
      <PageHero
        label="Governance"
        title={<>Review pending <em>approvals</em></>}
        subtitle="Approve or reject platform actions with a clear audit-friendly interface."
        actions={<div className="badge">{pendingCount} pending</div>}
      />
      {error && <p className="ck-alert ck-alert-re">{error}</p>}
      <div className="card">
        <div className="section-head"><div><h2>Approvals queue</h2><p className="section-copy">Decision workflow for discount, cancellation, and exceptional billing actions.</p></div></div>
        {loading ? <p>Loading…</p> : (
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
                      {can('order:approve') && a.status === 'PENDING' ? (
                        <div className="actions-inline">
                          <button type="button" disabled={deciding === a.id} onClick={() => void decide(a.id, 'approve')}>Approve</button>
                          <button type="button" className="secondary" disabled={deciding === a.id} onClick={() => void decide(a.id, 'reject')}>Reject</button>
                        </div>
                      ) : '-'}
                    </td>
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
