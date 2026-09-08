import { expect, test, type Page } from '@playwright/test';
import { signIn } from './support/session';

/**
 * Measures real horizontal overflow at mobile widths. WCAG 1.4.10 (Reflow)
 * requires content to reflow to a single column at 320px CSS width without
 * loss of content or function, and without two-dimensional scrolling.
 *
 * These routes currently reflow cleanly at every width measured, so this is a
 * regression gate rather than a fix: it locks in behaviour that already works.
 *
 * Coverage is honest about its limits. Only routes reachable with the mocked
 * session are checked; the data-dense panels (attendance grids, timetable, fee
 * tables) need far richer fixtures to render and are not covered here.
 */
const viewports = [
  { name: '320 (WCAG reflow floor)', width: 320, height: 720 },
  { name: '375 (iPhone SE)', width: 375, height: 720 },
  { name: '768 (tablet)', width: 768, height: 1024 },
];

const routes = [
  { name: 'login', path: '/login', auth: false },
  { name: 'dashboard', path: '/dashboard', auth: true },
  { name: 'schools', path: '/schools', auth: true },
];

async function overflow(page: Page) {
  return page.evaluate(() => {
    const docWidth = document.documentElement.clientWidth;
    const offenders: string[] = [];
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;
      if (rect.right > docWidth + 1) {
        const id = el.tagName.toLowerCase()
          + (el.className && typeof el.className === 'string'
            ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.')
            : '');
        offenders.push(`${id} (right=${Math.round(rect.right)} vs ${docWidth})`);
      }
    }
    return {
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: docWidth,
      offenders: Array.from(new Set(offenders)).slice(0, 6),
    };
  });
}

for (const vp of viewports) {
  for (const route of routes) {
    test(`overflow ${route.name} @ ${vp.name}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      if (route.auth) {
        await signIn(page);
        // signIn's catch-all returns [] for every collection, so tables render
        // empty and cannot demonstrate overflow. Register realistic rows AFTER
        // it: Playwright matches route handlers most-recently-registered first.
        await page.route('**/api/v1/schools*', (r) =>
          r.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(
              Array.from({ length: 12 }, (_, i) => ({
                id: i + 1,
                name: `Shri Guru Harkrishan Public Senior Secondary School ${i + 1}`,
                shortCode: `SGHPSSS${i + 1}`,
                city: 'Thiruvananthapuram',
                state: 'Kerala',
                active: true,
                adminEmail: `principal.administrator.${i + 1}@sghpsss-kerala.edu.in`,
                operationsEmail: `operations.coordinator.${i + 1}@sghpsss-kerala.edu.in`,
                academicYearId: 'ay-2026',
                configuredClassCount: 12,
                configuredSectionCount: 4,
              })),
            ),
          }));
      }
      await page.goto(route.path);
      await page.waitForLoadState('networkidle');
      const r = await overflow(page);
      const bleed = r.scrollWidth - r.clientWidth;
      expect(
        bleed,
        `${route.name} scrolls horizontally at ${vp.width}px (WCAG 1.4.10 reflow)`,
      ).toBeLessThanOrEqual(0);
    });
  }
}
