import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Stat } from '../ui';
import { formatMoney } from '../utils';

interface InvStats {
  sentThisMonth: number;
  paid: number;
  pending: number;
  totalInvoiced: number; // paise
}

export function SaRevenuePanel() {
  const [stats, setStats] = useState<InvStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    api
      .get<InvStats>('/sa/invoices/stats')
      .then((res) => setStats(res.data ?? null))
      .catch((e: any) => {
        setError(e?.response?.data?.message || 'Failed to load revenue stats.');
      })
      .finally(() => setLoading(false));
  }, []);

  return (
    <ModuleShell title="Revenue analytics" subtitle="Platform-wide invoice revenue and collection summary">
      {loading ? (
        <div className="ck-card" style={{ padding: 16 }}>Loading revenue stats…</div>
      ) : error ? (
        <div className="ck-card">
          <div className="ck-alert ck-alert-re" style={{ margin: 16 }}>
            <span>✕</span>
            <div>{error}</div>
          </div>
        </div>
      ) : !stats ? (
        <div className="ck-card" style={{ padding: 16 }}>No revenue data available.</div>
      ) : (
        <div className="ck-grid ck-grid-4">
          <Stat
            label="Sent this month"
            value={stats.sentThisMonth ?? 0}
            sub="Invoices issued"
            pill="Current"
            tone="blue"
          />
          <Stat
            label="Paid"
            value={stats.paid ?? 0}
            sub="Settled invoices"
            pill="Received"
            tone="green"
          />
          <Stat
            label="Pending"
            value={stats.pending ?? 0}
            sub="Awaiting payment"
            pill="Action"
            tone="orange"
          />
          <Stat
            label="Total invoiced (GMV)"
            value={`₹${formatMoney(Number(stats.totalInvoiced || 0) / 100)}`}
            sub="Grand total billed"
            pill="Paise→₹"
            tone="blue"
          />
        </div>
      )}
    </ModuleShell>
  );
}
