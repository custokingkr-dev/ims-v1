import { describe, expect, it } from 'vitest';
import {
  ACCOUNTANT_NAV_SECTIONS,
  ADMIN_NAV_SECTIONS,
  OPERATIONS_NAV_SECTIONS,
  TEACHER_NAV_SECTIONS,
  VIEWER_NAV_SECTIONS,
  filterNavSectionsForModules,
  type WorkspaceNavSection,
} from './config';

function navLabelsFor(activeModules: string[]): string[] {
  return filterNavSectionsForModules(ADMIN_NAV_SECTIONS, new Set(activeModules))
    .flatMap((section) => section.items.map((item) => item.label));
}

function filteredSectionsFor(
  sections: WorkspaceNavSection[],
  activeModules: string[],
): WorkspaceNavSection[] {
  return filterNavSectionsForModules(sections, new Set(activeModules));
}

describe('workspace nav module filtering', () => {
  it('keeps Dashboard under the top Overview section even when no modules are enabled', () => {
    const sections = filteredSectionsFor(ADMIN_NAV_SECTIONS, []);

    expect(sections[0]?.title).toBe('Overview');
    expect(sections[0]?.items).toEqual([
      expect.objectContaining({ key: 'home', label: 'Dashboard' }),
    ]);
    expect(sections[0]?.items[0]).not.toHaveProperty('module');
  });

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

  it.each([
    ['admin', ADMIN_NAV_SECTIONS],
    ['operations', OPERATIONS_NAV_SECTIONS],
    ['accountant', ACCOUNTANT_NAV_SECTIONS],
    ['teacher', TEACHER_NAV_SECTIONS],
    ['viewer', VIEWER_NAV_SECTIONS],
  ])('puts Dashboard under top Overview for %s nav', (_role, sections) => {
    const filtered = filteredSectionsFor(sections, ['ERP', 'SUPPLY_OS']);

    expect(filtered[0]?.title).toBe('Overview');
    expect(filtered[0]?.items[0]).toEqual(
      expect.objectContaining({ key: 'home', label: 'Dashboard' }),
    );
    expect(filtered[0]?.items[0]).not.toHaveProperty('module');
    expect(filtered.flatMap((section) => section.items).filter((item) => item.key === 'home')).toHaveLength(1);
  });
});
