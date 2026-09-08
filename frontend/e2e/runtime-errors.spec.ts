import { expect, test, type ConsoleMessage } from '@playwright/test';
import { signIn } from './support/session';

/**
 * Surfaces real runtime breakage: console errors, React warnings, uncaught
 * exceptions and failed requests. Reports rather than asserts — this run
 * establishes what is actually broken, as opposed to what static analysis
 * guesses might be.
 */
const routes = [
  { name: 'login', path: '/login', auth: false },
  { name: 'dashboard', path: '/dashboard', auth: true },
  { name: 'schools', path: '/schools', auth: true },
  { name: 'zones', path: '/zones', auth: true },
  { name: 'design-preview', path: '/design-preview', auth: false },
];

for (const route of routes) {
  test(`runtime errors: ${route.name}`, async ({ page }) => {
    const problems: string[] = [];

    page.on('console', (m: ConsoleMessage) => {
      if (m.type() === 'error' || m.type() === 'warning') {
        problems.push(`[${m.type()}] ${m.text().replace(/\s+/g, ' ').slice(0, 180)}`);
      }
    });
    page.on('pageerror', (e) => problems.push(`[pageerror] ${String(e).slice(0, 180)}`));
    page.on('requestfailed', (r) =>
      problems.push(`[requestfailed] ${r.url().slice(0, 90)} ${r.failure()?.errorText ?? ''}`));

    if (route.auth) await signIn(page);
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(700);

    const unique = Array.from(new Set(problems));
    expect(unique, `${route.name} produced console errors or failed requests`).toEqual([]);
  });
}
