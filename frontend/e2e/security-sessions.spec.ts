import { expect, test, type BrowserContext, type Route } from '@playwright/test';
import { user, workspace } from './support/session';

async function json(route: Route, body: unknown, status = 200, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });
}
async function authenticated(context: BrowserContext, override: (route: Route, pathname: string) => Promise<boolean>) {
  await context.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  await context.route('**/api/v1/**', async route => {
    const pathname = new URL(route.request().url()).pathname;
    if (await override(route, pathname)) return;
    if (pathname === '/api/v1/auth/refresh') return json(route, { ...user, permissions: [] });
    if (pathname === '/api/v1/reporting/workspace') return json(route, workspace);
    if (pathname.endsWith('/modules/active')) return json(route, []);
    return json(route, {});
  });
}

test('Web Locks serialize actual cross-tab cookie rotation and logout invalidates an admitted refresh', async ({ context, page }) => {
  let generation = 0, active = 0, maximum = 0, replay = 0, logout = 0;
  let release: (() => void) | undefined;
  let entered: (() => void) | undefined;
  let hold = false;
  const started = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  await context.addCookies([{ name: 'ck_refresh', value: 'rotation-0', domain: '127.0.0.1', path: '/api/v1/auth', httpOnly: true, sameSite: 'Lax' }]);
  await authenticated(context, async (route, pathname) => {
    if (pathname === '/api/v1/auth/refresh') {
      active++; maximum = Math.max(maximum, active);
      const cookie = await route.request().headerValue('cookie');
      if (!cookie?.includes(`ck_refresh=rotation-${generation}`)) { replay++; active--; await json(route, {}, 401); return true; }
      if (hold) { hold = false; entered?.(); await gate; }
      else await new Promise(resolve => setTimeout(resolve, 75));
      generation++; active--;
      await json(route, { ...user, permissions: [], accessToken: `memory-only-token-${generation}` }, 200,
        { 'set-cookie': `ck_refresh=rotation-${generation}; HttpOnly; SameSite=Lax; Path=/api/v1/auth` });
      return true;
    }
    if (pathname === '/api/v1/auth/logout') {
      logout++; await route.fulfill({ status: 204, headers: { 'set-cookie': 'ck_refresh=; HttpOnly; SameSite=Lax; Path=/api/v1/auth; Max-Age=0' } }); return true;
    }
    return false;
  });
  const other = await context.newPage();
  await Promise.all([page.goto('/dashboard'), other.goto('/dashboard')]);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(other.getByRole('button', { name: 'Sign out' })).toBeVisible();
  expect(maximum).toBe(1); expect(replay).toBe(0); expect(generation).toBe(2);
  expect(await page.evaluate(() => Boolean(navigator.locks?.request))).toBe(true);
  hold = true;
  const restoring = page.evaluate(async () => {
    const path = '/src/services/api.ts'; const transport = await import(/* @vite-ignore */ path);
    return transport.refreshToken();
  });
  await started;
  await other.getByRole('button', { name: 'Sign out' }).click();
  await expect(other).toHaveURL(/\/login$/);
  expect(logout).toBe(0); // Still waits behind the browser's origin-wide rotation lock.
  release?.();
  expect(await restoring).toBeNull();
  await expect.poll(() => logout).toBe(1);
  await expect(page).toHaveURL(/\/login$/);
  for (const tab of [page, other]) {
    const storage = await tab.evaluate(async () => {
      const path = '/src/services/api.ts'; const transport = await import(/* @vite-ignore */ path);
      return { token: transport.getAccessToken(), local: JSON.stringify(localStorage), session: JSON.stringify(sessionStorage), cookie: document.cookie };
    });
    expect(storage.token).toBeNull(); expect(storage.local + storage.session).not.toContain('memory-only-token');
    expect(storage.cookie).not.toContain('ck_refresh');
  }
  expect(replay).toBe(0); expect(maximum).toBe(1);
});

test('temporary refresh outage retains session intent and browser recovery retry restores the workspace', async ({ context, page }) => {
  let attempts = 0;
  await authenticated(context, async (route, pathname) => {
    if (pathname === '/api/v1/auth/refresh') {
      attempts++; await json(route, attempts === 1 ? {} : { ...user, permissions: [] }, attempts === 1 ? 503 : 200); return true;
    }
    return false;
  });
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Your session is temporarily unavailable' })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('custoking_isLoggedIn'))).toBe('true');
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  expect(attempts).toBe(2);
  expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain(user.accessToken);
});
