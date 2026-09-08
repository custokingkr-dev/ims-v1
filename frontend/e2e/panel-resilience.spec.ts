import { expect, test, type ConsoleMessage, type Page, type Route } from '@playwright/test';

/**
 * Resilience gate: every panel must survive a payload that violates the declared
 * contract without taking the whole workspace down.
 *
 * The declared TypeScript types say these sections are always present, so a
 * sparse payload is not something the current backend produces. The failure mode
 * is what matters: a guard like
 *
 *     if (!metrics) return null;
 *     const { fees } = metrics;
 *     fees.totalOverdueAmountPaise / 100
 *
 * checks the object but not its contents, so one missing field replaces the
 * entire screen with the ErrorBoundary rather than degrading one card. Contract
 * drift - a renamed field, a null for an array, a 200 carrying an error body -
 * costs everything.
 *
 * Every endpoint here returns {} deliberately.
 */
const SCHOOL_PERMISSIONS = [
  'student:read', 'student:create', 'student:import', 'student:photo-import', 'student:export',
  'attendance:read', 'timetable:read', 'staff:read', 'school:update', 'school:read',
  'fee:read', 'payment:read', 'fee_structure:read', 'order:read', 'plan:read',
  'firefighting:read', 'firefighting:create', 'firefighting:approve',
];

const ALL_MODULES = [
  'STUDENTS', 'ATTENDANCE', 'FEES', 'INVOICES', 'PAYMENTS',
  'ORDERS', 'REPORTS', 'FIREFIGHTING', 'SUPPLY_OS', 'ERP',
].map((moduleCode) => ({ moduleCode }));

async function signInWithSparsePayloads(page: Page, permissions: string[]) {
  const user = {
    accessToken: 'sparse-token', userId: 42, fullName: 'Asha Admin',
    email: 'asha@example.test', role: 'ADMIN', branchId: 7,
    branchName: 'Green Valley School', permissions,
  };
  await page.route('**/api/v1/**', async (route: Route) => {
    const pathname = new URL(route.request().url()).pathname;
    let body: unknown = {};
    if (pathname.includes('/auth/')) body = user;
    // Only the two payloads the shell itself needs are well-formed. Everything
    // a panel asks for comes back as {}.
    else if (pathname === '/api/v1/workspace') {
      body = { school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' }, dashboard: {}, orders: [], staff: [] };
    } else if (pathname.endsWith('/modules/active')) body = ALL_MODULES;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}

test('no panel crashes the workspace on a contract-violating payload', async ({ page }) => {
  test.setTimeout(180_000);

  const crashes: string[] = [];
  let current = 'dashboard';
  page.on('pageerror', (e) => crashes.push(`${current}: ${String(e).replace(/\s+/g, ' ').slice(0, 120)}`));

  await signInWithSparsePayloads(page, SCHOOL_PERMISSIONS);
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // The shell itself can crash before any panel is clicked, which would leave the
  // nav empty and make this gate look like a setup failure rather than a finding.
  if (await page.getByText('Something went wrong').isVisible().catch(() => false)) {
    crashes.push('dashboard (shell): ErrorBoundary replaced the workspace on load');
  }

  const labels = await page.evaluate(() =>
    Array.from(document.querySelectorAll('button.ck-nav-item'))
      .filter((b) => (b as HTMLElement).offsetParent !== null)
      .map((b) => b.getAttribute('aria-label') ?? '')
      .filter(Boolean));

  if (crashes.length === 0) {
    expect(labels.length, 'no nav items and no crash — the gate would silently pass').toBeGreaterThan(3);
  }

  for (const label of labels) {
    current = label;
    const button = page.locator(`button.ck-nav-item[aria-label="${label}"]`).first();
    if (!(await button.click({ timeout: 5000 }).then(() => true).catch(() => false))) continue;
    await page.waitForTimeout(250);
    if (await page.getByText('Something went wrong').isVisible().catch(() => false)) {
      crashes.push(`${label}: ErrorBoundary replaced the workspace`);
      await page.goto('/dashboard');
      await page.waitForLoadState('networkidle');
    }
  }

  for (const c of Array.from(new Set(crashes))) console.log('  CRASH ' + c);
  expect(Array.from(new Set(crashes))).toEqual([]);
});
