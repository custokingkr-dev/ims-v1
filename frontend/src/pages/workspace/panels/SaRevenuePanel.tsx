import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, PanelMessage, Stat } from '../ui';
import { formatMoney } from '../utils';

interface InvStats {
  sentThisMonth: number;
  paid: number;
  pending: number;
  totalInvoiced: number; // paise
  periodStart?: string;
  periodEndExclusive?: string;
  reportingTimeZone?: string;
}

export function SaRevenuePanel() {
  const [stats, setStats] = useState<InvStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    setError('');
    api
      .get<InvStats>('/sa/invoices/stats')
      .then((res) => setStats(res.data ?? null))
      .catch((e: any) => {
        setError(e?.response?.data?.message || 'Failed to load invoice statistics.');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  return (
    <ModuleShell title="Invoice analytics" subtitle="Platform invoice counts and billed value. GMV does not measure revenue, cash collected, or profit.">
      {loading ? (
        <div className="ck-card"><PanelMessage>Loading invoice statistics…</PanelMessage></div>
      ) : error ? (
        <div className="ck-card">
          <div className="ck-alert ck-alert-re" role="alert" style={{ margin: 16 }}>
            <div>{error}</div>
            <button className="ck-btn ck-btn-ghost" onClick={load}>Retry</button>
          </div>
        </div>
      ) : !stats ? (
        <div className="ck-card"><PanelMessage>No invoice data available.</PanelMessage></div>
      ) : (
        <div className="ck-grid ck-grid-4">
          <Stat
            label="Issued this month"
            value={stats.sentThisMonth ?? 0}
            sub={stats.periodStart
              ? `${new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${stats.periodStart}T00:00:00Z`))} · ${stats.reportingTimeZone || 'UTC'}`
              : 'Invoices issued in the current reporting month'}
            pill="Current"
            tone="blue"
          />
          <Stat
            label="Paid"
            value={stats.paid ?? 0}
            sub="Settled invoices · all time"
            tone="green"
          />
          <Stat
            label="Pending"
            value={stats.pending ?? 0}
            sub="Awaiting payment · all time"
            pill="Action"
            tone="orange"
          />
          <Stat
            label="Total invoiced (GMV)"
            value={`₹${formatMoney(Number(stats.totalInvoiced || 0) / 100)}`}
            sub="Grand total billed · all time"
            tone="blue"
          />
        </div>
      )}
    </ModuleShell>
  );
}
