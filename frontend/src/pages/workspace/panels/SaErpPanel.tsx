import { useEffect, useState } from 'react';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import type { DashboardCommandCenterResponse } from '../../../types/dashboardCommandCenter';
import { ModuleShell, Stat } from '../ui';
import { formatMoney } from '../utils';

export function SaErpPanel() {
  const [metrics, setMetrics] = useState<DashboardCommandCenterResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    fetchCommandCenterMetrics()
      .then((data) => setMetrics(data))
      .catch((e: any) => {
        setError(e?.response?.data?.message || 'Failed to load ERP metrics.');
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card" style={{ padding: 16 }}>Loading ERP metrics…</div>
      </ModuleShell>
    );
  }

  if (error) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card">
          <div className="ck-alert ck-alert-re" style={{ margin: 16 }}>
            <span>✕</span>
            <div>{error}</div>
          </div>
        </div>
      </ModuleShell>
    );
  }

  if (!metrics) {
    return (
      <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
        <div className="ck-card" style={{ padding: 16 }}>No ERP metrics available.</div>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
      {/* Fee collection */}
      <div style={{ marginBottom: 8, fontWeight: 600, fontSize: 13, color: 'var(--ink2)', paddingLeft: 4 }}>
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
          pill="Paise→₹"
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
      <div style={{ marginBottom: 8, fontWeight: 600, fontSize: 13, color: 'var(--ink2)', paddingLeft: 4 }}>
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
      <div style={{ marginBottom: 8, fontWeight: 600, fontSize: 13, color: 'var(--ink2)', paddingLeft: 4 }}>
        Vendor dues
      </div>
      <div className="ck-grid ck-grid-3" style={{ marginBottom: 20 }}>
        <Stat
          label="Total vendor dues"
          value={`₹${formatMoney(metrics.vendorDues.totalDuesPaise / 100)}`}
          sub="Unpaid vendor amounts"
          pill="Paise→₹"
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
      <div style={{ marginBottom: 8, fontWeight: 600, fontSize: 13, color: 'var(--ink2)', paddingLeft: 4 }}>
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
