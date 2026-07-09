import { describe, expect, it } from 'vitest';
import { ADMIN_NAV_SECTIONS, filterNavSectionsForModules } from './config';

function navLabelsFor(activeModules: string[]): string[] {
  return filterNavSectionsForModules(ADMIN_NAV_SECTIONS, new Set(activeModules))
    .flatMap((section) => section.items.map((item) => item.label));
}

describe('workspace nav module filtering', () => {
  it('shows Dashboard only once when ERP and Supply OS are both enabled', () => {
    const labels = navLabelsFor(['ERP', 'SUPPLY_OS']);

    expect(labels.filter((label) => label === 'Dashboard')).toHaveLength(1);
  });

  it('keeps Dashboard visible when only ERP is enabled', () => {
    const labels = navLabelsFor(['ERP']);

    expect(labels).toContain('Dashboard');
    expect(labels).toContain('Students');
    expect(labels).not.toContain('Supply Details');
  });

  it('keeps Dashboard visible when only Supply OS is enabled', () => {
    const labels = navLabelsFor(['SUPPLY_OS']);

    expect(labels).toContain('Dashboard');
    expect(labels).toContain('Supply Details');
    expect(labels).not.toContain('Students');
  });
});
