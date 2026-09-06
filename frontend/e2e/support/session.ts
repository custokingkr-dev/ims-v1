import type { Page, Route } from '@playwright/test';

export const user = {
  accessToken: 'e2e-access-token',
  userId: 42,
  fullName: 'Asha Admin',
  email: 'asha@example.test',
  role: 'ADMIN',
  branchId: 7,
  branchName: 'Green Valley School',
  permissions: [],
};

export const workspace = {
  school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' },
  dashboard: {},
  orders: [],
  staff: [],
};

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

export async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/login') return json(route, user);
    if (pathname === '/api/v1/auth/refresh') return json(route, user);
    if (pathname === '/api/v1/workspace') return json(route, workspace);
    return json(route, {});
  });
}

export async function signIn(page: Page) {
  await mockAuthenticatedApi(page);
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}
