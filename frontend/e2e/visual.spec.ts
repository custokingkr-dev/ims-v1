import { test } from '@playwright/test';
import { signIn } from './support/session';

// Default MUST NOT be 'before' or 'after': those folders are hand-curated
// baselines for manual comparison, and a routine `npx playwright test` run
// (no VISUAL_LABEL set) would silently overwrite one of them with whatever
// the working tree currently looks like. 'current' is a throwaway bucket
// that routine full-suite runs land in harmlessly; pass VISUAL_LABEL=before
// or VISUAL_LABEL=after explicitly when you actually mean to (re)capture
// one of those baselines.
const LABEL = process.env.VISUAL_LABEL ?? 'current';

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
