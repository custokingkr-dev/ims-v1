import { useEffect, useState } from 'react';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { PageHero } from '../components/PageHero';

interface DashboardData {
  totalInvoices: number;
  pendingApprovals: number;
  activeCustomers: number;
  totalRevenue: number;
  collectedAmount: number;
  outstandingAmount: number;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const { can } = usePermissions();
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    api.get<DashboardData>('/dashboard')
      .then((res) => setData(res.data))
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to load dashboard.';
        setError(msg);
      })
      .finally(() => setLoading(false));
  }, []);

  const firstName = user?.fullName?.split(' ')[0] || 'team';

  if (loading) return <p>Loading dashboard...</p>;
  if (error) return <p className="ck-alert ck-alert-re">{error}</p>;
  if (!data) return null;

  return (
    <div className="page-stack">
      <PageHero
        label="Overview"
        title={<>Welcome back, <em>{firstName}</em></>}
        subtitle="Track invoices, collections, approvals, and school operations from a unified operational workspace."
        actions={
          <>
            <span className="badge">{can('platform:admin') ? 'Super Admin' : 'Admin'} workspace</span>
            <span className="badge">{data.pendingApprovals} pending approvals</span>
          </>
        }
      />
      <div className="grid-3">
        <div className="metric-card"><span>Total invoices</span><strong>{data.totalInvoices}</strong><p>Generated across recent cycles.</p></div>
        <div className="metric-card"><span>Pending approvals</span><strong>{data.pendingApprovals}</strong><p>Credit notes and discount reviews awaiting action.</p></div>
        <div className="metric-card"><span>Active customers</span><strong>{data.activeCustomers}</strong><p>Schools, parents and B2B customers currently engaged.</p></div>
        <div className="metric-card"><span>Total revenue</span><strong>₹ {data.totalRevenue}</strong><p>Gross billed amount.</p></div>
        <div className="metric-card"><span>Collected</span><strong>₹ {data.collectedAmount}</strong><p>Payments already recorded.</p></div>
        <div className="metric-card"><span>Outstanding</span><strong>₹ {data.outstandingAmount}</strong><p>Open receivables requiring follow-up.</p></div>
      </div>
    </div>
  );
}
