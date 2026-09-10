import type { Page, Route } from '@playwright/test';

export const user = {
  accessToken: 'e2e-access-token',
  userId: 42,
  fullName: 'Asha Admin',
  email: 'asha@example.test',
  role: 'ADMIN',
  branchId: 7,
  branchName: 'Green Valley School',
  permissions: ['school:read', 'school:create', 'school:update', 'platform:admin'],
};

export const workspace = {
  school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' },
  dashboard: {},
  orders: [],
  staff: [],
};

/**
 * Overrides for the mocked session. The workspace nav is driven by three
 * independent inputs, and getting any of them wrong silently renders a
 * different nav than the one under test:
 *
 *   role         - picks the *_NAV_SECTIONS table.
 *   permissions  - `platform:admin` alone flips the whole app to the SUPERADMIN
 *                  nav regardless of `role`; every non-platform-admin item is
 *                  additionally gated per-panel.
 *   modules      - GET /schools/{id}/modules/active. Every ADMIN nav item
 *                  carries a `module`, so an unmocked response collapses the
 *                  ADMIN nav to a single "Dashboard" entry.
 */
export type SessionOverrides = {
  role?: string;
  permissions?: string[];
  fullName?: string;
  branchId?: number;
  /** Module codes returned by GET /schools/{branchId}/modules/active. */
  modules?: string[];
  /** Pending count from GET /sa/invoices/stats - drives .ck-nav-badge. */
  pendingInvoices?: number;
};

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

export async function mockAuthenticatedApi(page: Page, overrides: SessionOverrides = {}) {
  const { modules, pendingInvoices, ...userOverrides } = overrides;
  const sessionUser = { ...user, ...userOverrides };
  const activeModules = (modules ?? []).map((moduleCode) => ({ moduleCode }));

  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/login') return json(route, sessionUser);
    if (pathname === '/api/v1/auth/refresh') return json(route, sessionUser);
    if (pathname === '/api/v1/workspace') return json(route, workspace);
    if (pathname.endsWith('/modules/active')) return json(route, activeModules);
    if (pathname === '/api/v1/sa/invoices') return json(route, []);
    if (pathname === '/api/v1/sa/invoices/stats') {
      return json(route, { pending: pendingInvoices ?? 0, total: pendingInvoices ?? 0 });
    }
    if (pathname === '/api/v1/schools') return json(route, []);
    if (pathname === '/api/v1/zones') return json(route, []);
    return json(route, {});
  });
}

export async function signIn(page: Page) {
  await mockAuthenticatedApi(page);
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}

/**
 * signIn with a specific role / permission / entitlement combination. Prefer
 * this over signIn whenever the thing under test varies by role.
 */
export async function signInAs(page: Page, overrides: SessionOverrides) {
  await mockAuthenticatedApi(page, overrides);
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}
