import { expect, test, type ConsoleMessage, type Page, type Route } from '@playwright/test';

/**
 * Walks every workspace panel reachable from the nav and checks each for runtime
 * errors and horizontal overflow.
 *
 * Panels are React state rather than routes, so they cannot be reached by URL.
 * This enumerates the nav at runtime and clicks through it, which means coverage
 * follows the app instead of a hardcoded list.
 *
 * Panels are gated by MODULE ENTITLEMENTS as well as permissions: the nav filters
 * on activeModules, sourced from /schools/:id/modules/active. Mocking that as an
 * empty list leaves a school admin with ONE visible nav item, so a sweep that does
 * not grant modules covers almost nothing while still reporting success.
 *
 * Two personas are required. UnifiedWorkspacePage derives
 * isPlatformAdmin = role === 'SUPERADMIN' || can('platform:admin'), and that flag
 * swaps the entire nav. Granting every permission at once therefore HIDES the
 * school panels (students, attendance, timetable, fees) behind the platform-admin
 * nav - a sweep with one all-powerful persona silently covers half the app.
 */
const SCHOOL_PERMISSIONS = [
  'student:read', 'student:create', 'student:import', 'student:photo-import', 'student:export',
  'attendance:read', 'timetable:read', 'staff:read', 'school:update', 'school:read',
  'fee:read', 'payment:read', 'fee_structure:read', 'order:read', 'plan:read',
  'firefighting:read', 'firefighting:create', 'firefighting:approve',
];
const PLATFORM_PERMISSIONS = [...SCHOOL_PERMISSIONS, 'platform:admin'];

const personas = [
  { name: 'school admin', role: 'ADMIN', permissions: SCHOOL_PERMISSIONS },
  { name: 'platform admin', role: 'ADMIN', permissions: PLATFORM_PERMISSIONS },
];

const ALL_MODULES = [
  'STUDENTS', 'ATTENDANCE', 'FEES', 'INVOICES', 'PAYMENTS',
  'ORDERS', 'REPORTS', 'FIREFIGHTING', 'SUPPLY_OS', 'ERP',
].map((moduleCode) => ({ moduleCode }));

/**
 * A contract-valid dashboard payload. DashboardCommandCenterResponse declares all
 * six sections as required, and ActionInsightsSection destructures them and reads
 * fees.totalOverdueAmountPaise without a per-section guard. Returning {} here
 * therefore takes the whole dashboard into the ErrorBoundary - a mock artifact,
 * since the backend contract says the sections are always present, but worth
 * knowing that a contract violation costs the entire panel rather than one card.
 */
const commandCenter = {
  fees: { defaulterCount: 3, totalOverdueAmountPaise: 1250000, oldestDueDays: 12, totalDefaulters: 3 },
  photography: { eventId: null, collectedAmount: 0, pendingAmount: 0, targetAmount: 0, pendingReviewCount: 0, longAbsenceCount: 0 },
  lifecycle: { pendingReviewCount: 2, longAbsenceCount: 1, sectionsBelowThresholdCount: 0, thresholdPercent: 75 },
  attendance: { sectionsBelowThresholdCount: 1, thresholdPercent: 75, eventId: null, title: null },
  vendorDues: { catalogOrderCount: 0, catalogOrderTotalPaise: 0, firefightingCount: 0, firefightingTotalPaise: 0, totalDuesPaise: 0 },
  reorderSignals: { alertCount: 0 },
};

/** Shape from PhotoImportService.context(): the panel does schools.find(...). */
const photoImportContext = {
  driveConfigured: true,
  managedDriveConfigured: true,
  schools: [{ id: 7, uid: 'school-uid-7', name: 'Green Valley School', academicYearId: 'ay-2026' }],
  mappingColumns: ['AdmissionNo', 'Name', 'Class', 'Section', 'ImageNo'],
  mappingFileFormats: ['XLSX', 'XLS', 'CSV', 'TSV'],
  mappingRowLimit: 5000,
  imageFileLimit: '20 MB per image',
  fileNameRule: 'DSC5236.jpg',
};

/** StudentExportPanel reads context.schools.length on mount. */
const exportContext = {
  schools: [{
    id: 7, name: 'Green Valley School', shortCode: 'GVS',
    studentCount: 1240, photoCount: 1180,
  }],
  fileNameRule: 'admissionNo.jpg',
  workbookFileName: 'students.xlsx',
};

/** FeeModulePanel reads years.find(...) and structure.bands.find(...). */
const academicYears = [{ id: 'ay-2026', label: '2026-27', active: true }];
const feeStructure = { bands: [], assignments: [] };

const workspace = {
  school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' },
  dashboard: {}, orders: [], staff: [],
};

async function signInAs(page: Page, role: string, permissions: string[]) {
  const user = {
    accessToken: 'sweep-token', userId: 42, fullName: 'Asha Admin',
    email: 'asha@example.test', role, branchId: 7,
    branchName: 'Green Valley School', permissions,
  };
  await page.route('**/api/v1/**', async (route: Route) => {
    const pathname = new URL(route.request().url()).pathname;
    let body: unknown = {};
    if (pathname.includes('/auth/')) body = user;
    else if (pathname === '/api/v1/workspace') body = workspace;
    else if (pathname.endsWith('/modules/active')) body = ALL_MODULES;
    else if (pathname.endsWith('/student-photo-imports/context')) body = photoImportContext;
    else if (pathname.endsWith('/students/export/context')) body = exportContext;
    else if (pathname.includes('academic-year')) body = academicYears;
    else if (pathname.includes('/fees/structure') || pathname.includes('fee-structure')) body = feeStructure;
    else if (pathname.includes('command-center') || pathname.includes('command-centre/brief')) body = commandCenter;
    else if (/(list|students|schools|zones|staff|orders|invoices|plans|sections|classes|years)$/.test(pathname)) body = [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}

async function visibleNavLabels(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('button.ck-nav-item'))
      .filter((b) => (b as HTMLElement).offsetParent !== null)
      .map((b) => b.getAttribute('aria-label') ?? '')
      .filter(Boolean));
}

for (const persona of personas) {
  test(`panels render without runtime errors: ${persona.name}`, async ({ page }) => {
    test.setTimeout(180_000); // panels are lazy-loaded; 27 chunk fetches exceed the 30s default
    const problems: string[] = [];
    page.on('console', (m: ConsoleMessage) => {
      if (m.type() === 'error') problems.push(m.text().replace(/\s+/g, ' ').slice(0, 150));
    });
    page.on('pageerror', (e) => problems.push(`[pageerror] ${String(e).slice(0, 150)}`));

    await signInAs(page, persona.role, persona.permissions);
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');

    const labels = await visibleNavLabels(page);
    expect(labels.length, 'no nav items found - the sweep would silently pass').toBeGreaterThan(3);
    console.log(`${persona.name}: sweeping ${labels.length} panels`);

    const broken: string[] = [];
    for (const label of labels) {
      const before = problems.length;
      const button = page.locator(`button.ck-nav-item[aria-label="${label}"]`).first();
      const clicked = await button.click({ timeout: 5000 }).then(() => true).catch(() => false);
      if (!clicked) { broken.push(`${label}: nav item not reachable`); }
      await page.waitForTimeout(300);
      for (const p of problems.slice(before)) broken.push(`${label}: ${p}`);
      // One panel crashing replaces the whole app with the ErrorBoundary, which
      // would make every later panel look broken too. Reload so each panel is
      // judged on its own.
      if (await page.getByText('Something went wrong').isVisible().catch(() => false)) {
        await page.goto('/dashboard');
        await page.waitForLoadState('networkidle');
      }
    }

    for (const b of broken) console.log('  BROKEN ' + b);
    expect(broken).toEqual([]);
  });

  test(`panels reflow at 320px: ${persona.name}`, async ({ page }) => {
    test.setTimeout(180_000);
    await page.setViewportSize({ width: 320, height: 900 });
    await signInAs(page, persona.role, persona.permissions);
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');

    const labels = await visibleNavLabels(page);
    expect(labels.length).toBeGreaterThan(3);

    const overflowing: string[] = [];
    for (const label of labels) {
      const button = page.locator(`button.ck-nav-item[aria-label="${label}"]`).first();
      const clicked = await button.click({ timeout: 5000 }).then(() => true).catch(() => false);
      if (!clicked) continue;
      await page.waitForTimeout(300);
      const bleed = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      if (bleed > 0) overflowing.push(`${label}: ${bleed}px`);
    }

    for (const o of overflowing) console.log('  OVERFLOW ' + o);
    expect(overflowing).toEqual([]);
  });
}
