import { ModuleShell } from '../ui';
import { EmptyState } from '../../../shared/components/EmptyState';

export function SaRevenuePanel() {
  return (
    <ModuleShell title="Revenue analytics" subtitle="Platform-wide revenue and order value trends">
      <div className="ck-card" style={{ padding: '32px 24px' }}>
        <EmptyState
          icon="₹"
          message="Revenue analytics coming soon"
          description="This panel will display GMV, order value trends, school-wise contribution, and payment reconciliation summaries."
        />
      </div>
    </ModuleShell>
  );
}
