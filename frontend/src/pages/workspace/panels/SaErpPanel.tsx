import { ModuleShell } from '../ui';
import { EmptyState } from '../../../shared/components/EmptyState';

export function SaErpPanel() {
  return (
    <ModuleShell title="ERP activity" subtitle="School ERP activity across all tenants">
      <div className="ck-card" style={{ padding: '32px 24px' }}>
        <EmptyState
          icon="📊"
          message="ERP activity analytics coming soon"
          description="This panel will surface cross-school ERP metrics — attendance trends, fee collection rates, and operational health signals."
        />
      </div>
    </ModuleShell>
  );
}
