import { useEffect, useState } from 'react';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import type { DashboardCommandCenterResponse } from '../../../types/dashboardCommandCenter';
import { ModuleShell, PanelMessage, Stat } from '../ui';
import { formatMoney } from '../utils';

export function SaErpPanel() {
  const [metrics, setMetrics] = useState<DashboardCommandCenterResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    setError('');
    setMetrics(null);
    fetchCommandCenterMetrics()
      .then((data) => setMetrics(data))
      .catch((e: any) => {
        setError(e?.response?.data?.message || 'Failed to load ERP metrics.');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  if (loading) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card"><PanelMessage>Loading ERP metrics…</PanelMessage></div>
      </ModuleShell>
    );
  }

  if (error) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card">
          <div className="ck-alert ck-alert-re" role="alert" style={{ margin: 16 }}>
            <div>{error}</div>
            <button className="ck-btn ck-btn-ghost" onClick={load}>Retry</button>
          </div>
        </div>
      </ModuleShell>
    );
  }

  // Every section below is dereferenced directly, so a response that is not the expected shape
  // used to throw during render and take the whole application into the global error boundary,
  // losing the nav and every other panel with it. A panel that cannot read its data shows that
  // in its own error state instead.
  const sections = ['fees', 'lifecycle', 'attendance', 'vendorDues', 'reorderSignals'] as const;
  const usable = !!metrics && typeof metrics === 'object'
    && sections.every((section) => metrics[section] && typeof metrics[section] === 'object');

  if (metrics && !usable) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card">
          <div className="ck-alert ck-alert-re" role="alert" style={{ margin: 16 }}>
            <div>ERP metrics could not be read.</div>
            <button className="ck-btn ck-btn-ghost" onClick={load}>Retry</button>
          </div>
        </div>
      </ModuleShell>
    );
  }

  if (!metrics) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card"><PanelMessage>No ERP metrics available.</PanelMessage></div>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
      {/* Fee collection */}
      <div className="section-label" style={{ marginBottom: 8, paddingLeft: 4 }}>
        Fee collection
      </div>
      <div className="ck-grid ck-grid-3" style={{ marginBottom: 20 }}>
        <Stat
          label="Fee defaulters"
          value={metrics.fees.defaulterCount}
          sub="Students with overdue fees"
          pill="Fees"
          tone="red"
        />
        <Stat
          label="Total overdue"
          value={`₹${formatMoney(metrics.fees.totalOverdueAmountPaise / 100)}`}
          sub="Overdue fee amount"
          tone="orange"
        />
        <Stat
          label="Oldest overdue"
          value={`${metrics.fees.oldestDueDays}d`}
          sub="Days past due date"
          pill="Oldest"
          tone="orange"
        />
      </div>

      {/* Attendance */}
      <div className="section-label" style={{ marginBottom: 8, paddingLeft: 4 }}>
        Attendance
      </div>
      <div className="ck-grid ck-grid-2" style={{ marginBottom: 20 }}>
        <Stat
          label="Low attendance sections"
          value={metrics.attendance.sectionsBelowThresholdCount}
          sub={`Below ${metrics.attendance.thresholdPercent}% threshold`}
          pill="Alert"
          tone="orange"
        />
        <Stat
          label="Long absence students"
          value={metrics.lifecycle.longAbsenceCount}
          sub="Extended absence cases"
          pill="Lifecycle"
          tone="red"
        />
      </div>

      {/* Vendor dues */}
      <div className="section-label" style={{ marginBottom: 8, paddingLeft: 4 }}>
        Vendor dues
      </div>
      <div className="ck-grid ck-grid-3" style={{ marginBottom: 20 }}>
        <Stat
          label="Total vendor dues"
          value={`₹${formatMoney(metrics.vendorDues.totalDuesPaise / 100)}`}
          sub="Unpaid vendor amounts"
          tone="orange"
        />
        <Stat
          label="Catalog orders"
          value={metrics.vendorDues.catalogOrderCount}
          sub="Pending catalog payments"
          pill="Catalog"
          tone="blue"
        />
        <Stat
          label="Firefighting"
          value={metrics.vendorDues.firefightingCount}
          sub="Pending FF payments"
          pill="FF"
          tone="blue"
        />
      </div>

      {/* Student lifecycle & reorder */}
      <div className="section-label" style={{ marginBottom: 8, paddingLeft: 4 }}>
        Student lifecycle &amp; inventory
      </div>
      <div className="ck-grid ck-grid-2">
        <Stat
          label="Pending reviews"
          value={metrics.lifecycle.pendingReviewCount}
          sub="Student data reviews"
          pill="Review"
          tone="orange"
        />
        <Stat
          label="Reorder alerts"
          value={metrics.reorderSignals.alertCount}
          sub="Inventory reorder signals"
          pill="Reorder"
          tone="red"
        />
      </div>
    </ModuleShell>
  );
}
