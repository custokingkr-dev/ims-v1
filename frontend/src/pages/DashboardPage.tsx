import { useEffect, useState } from 'react';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';

export default function DashboardPage() {
  const { user } = useAuth();
  const [data, setData] = useState<any>(null);
  useEffect(() => { api.get('/dashboard').then((res) => setData(res.data)); }, []);

  if (!data) return <p>Loading dashboard...</p>;

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-row">
          <div>
            <div className="section-label">Overview</div>
            <h1 className="page-title">Welcome back, <em>{user?.fullName?.split(' ')[0] || 'team'}</em></h1>
            <p className="page-subtitle">Track invoices, collections, approvals, and school operations from a unified operational workspace.</p>
          </div>
          <div className="hero-actions">
            <span className="badge">{user?.role === 'SUPERADMIN' ? 'Super Admin' : 'Admin'} workspace</span>
            <span className="badge">{data.pendingApprovals} pending approvals</span>
          </div>
        </div>
      </section>

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
