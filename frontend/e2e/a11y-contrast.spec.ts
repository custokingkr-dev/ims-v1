import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { signIn } from './support/session';

/**
 * Colour-contrast gate.
 *
 * Static analysis of the JSX proved untrustworthy for accessibility work —
 * attributes contain nested braces that defeat regex, and controls that look
 * unlabelled usually carry aria-label. axe reads the rendered accessibility
 * tree, which is what assistive technology actually sees. When first run it
 * reported exactly one violation type across every route: color-contrast,
 * 22 nodes, of which 21 were the muted-grey text token.
 *
 * Scoped to color-contrast deliberately. Widening it to all of WCAG AA is
 * worthwhile but is a separate piece of work with its own findings to triage.
 */
const routes = [
  { name: 'login', path: '/login', auth: false },
  { name: 'dashboard', path: '/dashboard', auth: true },
  { name: 'schools', path: '/schools', auth: true },
  { name: 'zones', path: '/zones', auth: true },
];

for (const route of routes) {
  test(`no colour-contrast violations: ${route.name}`, async ({ page }) => {
    if (route.auth) await signIn(page);
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2aa'])
      .include('body')
      .analyze();

    const contrast = results.violations.filter((v) => v.id === 'color-contrast');
    const failures = contrast.flatMap((v) =>
      v.nodes.map((n) => (n.any[0]?.message ?? '').replace(/\s+/g, ' ').slice(0, 120)),
    );

    expect(failures).toEqual([]);
  });
}
