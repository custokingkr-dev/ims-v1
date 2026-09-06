import { test } from '@playwright/test';
import { signIn } from './support/session';

const LABEL = process.env.VISUAL_LABEL ?? 'before';

const publicRoutes = [
  { name: 'login', path: '/login' },
  { name: 'design-preview', path: '/design-preview' },
];

const protectedRoutes = [
  { name: 'dashboard', path: '/dashboard' },
  { name: 'schools', path: '/schools' },
  { name: 'zones', path: '/zones' },
];

for (const route of publicRoutes) {
  test(`capture ${route.name}`, async ({ page }) => {
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');
    await page.screenshot({
      path: `test-results/visual/${LABEL}/${route.name}.png`,
      fullPage: true,
    });
  });
}

for (const route of protectedRoutes) {
  test(`capture ${route.name}`, async ({ page }) => {
    await signIn(page);
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');
    await page.screenshot({
      path: `test-results/visual/${LABEL}/${route.name}.png`,
      fullPage: true,
    });
  });
}
