import { ModuleShell } from '../ui';
import { ProductCatalogManager } from '../../../features/catalog/ProductCatalogManager';

export function SaCatalogPanel() {
  return (
    <ModuleShell title="Catalog management" subtitle="Products, notebook specifications and order conditions">
      <ProductCatalogManager />
    </ModuleShell>
  );
}
