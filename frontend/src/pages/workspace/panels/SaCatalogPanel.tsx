import { ModuleShell } from '../ui';

export function SaCatalogPanel() {
  return (
    <ModuleShell title="Catalog management" subtitle="Platform-wide catalog item and pricing controls">
      <div className="ck-card">
        <div style={{ padding: 24, color: 'var(--ink2)' }}>
          Catalog management is coming soon. There is no superadmin catalog-CRUD backend yet —
          this panel will let you manage catalog items and pricing across all schools once it ships.
        </div>
      </div>
    </ModuleShell>
  );
}
