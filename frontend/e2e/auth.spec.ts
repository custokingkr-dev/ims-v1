import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
  accessToken: 'e2e-access-token',
  userId: 42,
  fullName: 'Asha Admin',
  email: 'asha@example.test',
  role: 'ADMIN',
  branchId: 7,
  branchName: 'Green Valley School',
  permissions: [],
};

const workspace = {
  school: {
    name: 'Green Valley School',
    meta: '2026-27',
    timeZone: 'Asia/Kolkata',
  },
  dashboard: {},
  orders: [],
  staff: [],
};

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;

    if (pathname === '/api/v1/auth/login') return json(route, user);
    if (pathname === '/api/v1/auth/refresh') return json(route, user);
    if (pathname === '/api/v1/workspace') return json(route, workspace);
    if (pathname === '/api/v1/schools/7/modules/active') return json(route, []);
    if (pathname === '/api/v1/command-centre/actions') return json(route, []);
    if (pathname === '/api/v1/notifications/broadcasts') return json(route, []);
    if (pathname === '/api/v1/command-centre/feed') return json(route, []);

    return json(route, {});
  });
}

test('a protected route sends an anonymous user to sign in', async ({ page }) => {
  await page.goto('/dashboard');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test('login establishes an in-memory session and opens the protected workspace', async ({ page }) => {
  await mockAuthenticatedApi(page);
  const loginRequest = page.waitForRequest((request) =>
    request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/auth/login');

  await page.goto('/login');
  await page.getByLabel('Email address').fill('asha@example.test');
  await page.locator('#pw-input').fill('correct horse battery staple');
  await page.getByRole('button', { name: 'Sign in' }).click();

  expect((await loginRequest).postDataJSON()).toEqual({
    email: 'asha@example.test',
    password: 'correct horse battery staple',
  });
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.evaluate(() => localStorage.getItem('custoking_isLoggedIn'))).resolves.toBe('true');
});

test('a returning session is restored before the protected route is evaluated', async ({ page }) => {
  await mockAuthenticatedApi(page);
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  const refreshRequest = page.waitForRequest((request) =>
    request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/auth/refresh');

  await page.goto('/dashboard');

  await refreshRequest;
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'School dashboard' })).toBeVisible();
});
