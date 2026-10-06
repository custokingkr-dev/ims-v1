import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';
import { user, workspace } from './support/session';

async function json(route: Route, body: unknown, status = 200, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });
}
async function authenticated(context: BrowserContext, passkey: (route: Route, pathname: string) => Promise<boolean>) {
  await context.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  await context.route('**/api/v1/**', async route => {
    const pathname = new URL(route.request().url()).pathname;
    if (await passkey(route, pathname)) return;
    if (pathname === '/api/v1/auth/refresh') return json(route, { ...user, permissions: [] });
    if (pathname === '/api/v1/reporting/workspace') return json(route, workspace);
    if (pathname.endsWith('/modules/active')) return json(route, []);
    return json(route, {});
  });
}
async function openVerification(page: Page, url = '/dashboard') {
  await page.goto(url);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('custoking-step-up-required')));
  await expect(page.getByRole('dialog')).toBeVisible();
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

test('passkey enrollment and step-up use actual browser WebAuthn virtual authenticator without credential persistence', async ({ context, page }) => {
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal',
    hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });
  const challenge = Buffer.alloc(32, 7).toString('base64url');
  let registered: Record<string, any> | undefined, asserted: Record<string, any> | undefined;
  let sensitiveRequests = 0;
  await authenticated(context, async (route, pathname) => {
    if (pathname === '/api/v1/security/fixture-action') {
      sensitiveRequests++; await json(route, { code: 'STEP_UP_REQUIRED' }, 403); return true;
    }
    if (pathname === '/api/v1/auth/passkeys') { await json(route, { enrolled: false }); return true; }
    if (pathname.endsWith('/passkeys/registration/options')) {
      expect(route.request().postDataJSON()).toEqual({ password: 'current-password-test-only' });
      await json(route, { challengeId: 'registration-fixture', publicKey: { challenge, rp: { name: 'IMS browser test', id: 'localhost' },
        user: { id: Buffer.from('browser-user').toString('base64url'), name: user.email, displayName: user.fullName },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' }, attestation: 'none' } }); return true;
    }
    if (pathname.endsWith('/passkeys/registration/verify')) { registered = route.request().postDataJSON(); await json(route, {}); return true; }
    if (pathname.endsWith('/passkeys/assertion/options')) {
      await json(route, { challengeId: 'assertion-fixture', publicKey: { challenge, rpId: 'localhost', userVerification: 'required',
        allowCredentials: [{ type: 'public-key', id: registered?.credential.id }] } }); return true;
    }
    if (pathname.endsWith('/passkeys/assertion/verify')) { asserted = route.request().postDataJSON(); await json(route, {}); return true; }
    return false;
  });
  await page.goto('http://localhost:4173/dashboard');
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await page.evaluate(async () => {
    const path = '/src/services/api.ts'; const transport = await import(/* @vite-ignore */ path);
    try { await transport.default.post('/security/fixture-action', { sensitive: true }); } catch { /* Step-up deliberately does not replay. */ }
  });
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByLabel('Current password').fill('current-password-test-only');
  await page.getByRole('button', { name: 'Create and verify passkey' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(registered?.challengeId).toBe('registration-fixture'); expect(asserted?.challengeId).toBe('assertion-fixture');
  expect(asserted?.credential.id).toBe(registered?.credential.id);
  const registrationData = JSON.parse(Buffer.from(registered?.credential.response.clientDataJSON, 'base64url').toString());
  const assertionData = JSON.parse(Buffer.from(asserted?.credential.response.clientDataJSON, 'base64url').toString());
  expect(registrationData.type).toBe('webauthn.create'); expect(assertionData.type).toBe('webauthn.get');
  expect(registrationData.challenge).toBe(challenge); expect(assertionData.challenge).toBe(challenge);
  expect(assertionData.origin).toBe('http://localhost:4173');
  expect(asserted?.credential.response.signature.length).toBeGreaterThan(32);
  const flags = Buffer.from(asserted?.credential.response.authenticatorData, 'base64url')[32];
  expect(flags & 0x01).toBe(0x01); expect(flags & 0x04).toBe(0x04); // Authenticator confirmed presence + verification.
  expect(sensitiveRequests).toBe(1);
  const stored = await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage));
  for (const forbidden of ['current-password-test-only', user.accessToken, registered?.credential.id, 'attestationObject', 'authenticatorData']) expect(stored).not.toContain(forbidden);
});

test('unknown passkey status keeps verification disabled and displays recoverable error', async ({ context, page }) => {
  await authenticated(context, async (route, pathname) => {
    if (pathname === '/api/v1/auth/passkeys') { await json(route, {}, 503); return true; }
    return false;
  });
  await openVerification(page);
  await expect(page.getByRole('alert')).toContainText('Passkey settings are unavailable');
  await expect(page.getByRole('button', { name: 'Create and verify passkey' })).toBeDisabled();
  await expect(page.getByLabel('Current password')).toHaveCount(0);
});

test('unsupported browser shows passkey recovery guidance and cannot bypass verification', async ({ context, page }) => {
  await context.addInitScript(() => Object.defineProperty(window, 'PublicKeyCredential', { value: undefined, configurable: true }));
  await authenticated(context, async (route, pathname) => {
    if (pathname === '/api/v1/auth/passkeys') { await json(route, { enrolled: true }); return true; }
    return false;
  });
  await openVerification(page);
  await expect(page.getByRole('alert')).toContainText('This browser does not support passkeys');
  await expect(page.getByRole('button', { name: 'Verify with passkey' })).toBeDisabled();
  await expect(page.getByText('audited recovery process')).toBeVisible();
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
