import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signInAs } from './support/session';

/**
 * The superadmin portal was outside every existing audit: a11y-contrast and
 * responsive-audit cover login, dashboard, schools and zones, and none of the
 * eleven `sa-*` panels appeared in any of them. This sweep closes that gap.
 *
 * Contrast is read from the rendered accessibility tree rather than the JSX,
 * for the reason a11y-contrast.spec.ts already records: attributes defeat
 * regex, and axe sees what assistive technology sees.
 */
const PANELS = [
  'All orders',
  'New order request',
  'Invoices',
  'Order approvals',
  'School accounts',
  'ERP activity',
  'Revenue',
  'Catalog mgmt',
];

async function openPortal(page: Page) {
  await signInAs(page, {
    role: 'SUPERADMIN',
    fullName: 'Super Admin',
    permissions: ['platform:admin', 'order:create', 'order:read', 'order:update', 'catalog:manage', 'school:read'],
    modules: ['ORDERS', 'SUPPLY_OS', 'ERP'],
  });
  await page.goto('/dashboard');
  // The nav is a hover rail with a width transition, so Playwright never sees a nav item
  // settle and every click times out waiting for stability. Motion is not what this sweep
  // measures.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addStyleTag({ content: '*,*::before,*::after{transition:none !important;animation:none !important}' });
}

/** The nav is a hover rail; leaving the pointer on it keeps the layout moving. */
async function park(page: Page) {
  const size = page.viewportSize();
  await page.mouse.move((size?.width ?? 1280) - 40, (size?.height ?? 800) - 40);
  await page.waitForTimeout(250);
}

/**
 * Always select at desktop width. The narrow-width nav is a drawer behind a scrim that
 * intercepts the click, and driving it is not what this sweep measures: the panel is.
 * Reflow tests resize afterwards, which is the same thing a user rotating a tablet does.
 */
async function openPanel(page: Page, label: string) {
  await page.setViewportSize({ width: 1280, height: 900 });
  const item = page.locator('button.ck-nav-item').filter({ hasText: label }).first();
  await item.scrollIntoViewIfNeeded();
  await item.click();
  await park(page);
}

for (const label of PANELS) {
  test(`no colour-contrast violations: ${label}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openPortal(page);
    await openPanel(page, label);
    const results = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
    // Carry axe's measured ratio and the exact colours: a bare selector sends the reader
    // guessing which background the element actually sat on.
    const detail = results.violations.flatMap((v) => v.nodes.map((n) => {
      const data = (n.any?.[0] as { data?: Record<string, unknown> } | undefined)?.data ?? {};
      return { html: n.html.slice(0, 90), ratio: data.contrastRatio, fg: data.fgColor, bg: data.bgColor,
               size: data.fontSize, weight: data.fontWeight };
    }));
    expect(results.violations, JSON.stringify(detail, null, 2)).toEqual([]);
  });
}

for (const width of [375, 768] as const) {
  for (const label of PANELS) {
    test(`no horizontal overflow: ${label} @ ${width}`, async ({ page }) => {
      await openPortal(page);
      await openPanel(page, label);
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(250);
      // A page wider than its viewport forces sideways scrolling, which WCAG reflow forbids.
      // Naming the offending elements, as responsive-audit.spec.ts does, keeps a failure
      // actionable instead of reporting a number nobody can locate.
      const overflow = await page.evaluate(() => {
        const docWidth = document.documentElement.clientWidth;
        const offenders: string[] = [];
        // An element inside a horizontal scroll container is meant to be wider than the
        // viewport: that is what the container is for, and the page itself does not scroll.
        const inScroller = (el: HTMLElement) => {
          for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            const ox = getComputedStyle(p).overflowX;
            if (ox === 'auto' || ox === 'scroll') return true;
          }
          return false;
        };
        for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
          const rect = el.getBoundingClientRect();
          if (rect.width === 0 || rect.height === 0) continue;
          if (inScroller(el)) continue;
          if (rect.right > docWidth + 1) {
            const cls = typeof el.className === 'string' && el.className.trim()
              ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
            offenders.push(`${el.tagName.toLowerCase()}${cls} (right=${Math.round(rect.right)} vs ${docWidth})`);
          }
        }
        return { scroll: document.documentElement.scrollWidth, client: docWidth,
                 offenders: Array.from(new Set(offenders)).slice(0, 6) };
      });
      expect(overflow.offenders, `${label} @ ${width}: ${overflow.offenders.join(' | ')}`).toEqual([]);
      // And the page itself must not scroll sideways, whatever its parts do.
      expect(overflow.scroll, `${label} @ ${width} scrolls sideways`).toBeLessThanOrEqual(overflow.client + 1);
    });
  }
}

/**
 * The nav and every input announce keyboard focus with a 2px green ring. Buttons announced it
 * with the browser default — a 1px near-black hairline at zero offset — which on a filled dark
 * green primary button is no announcement at all. Measured from the browser, because what a
 * stylesheet declares and what a control actually draws are different questions.
 */
test('keyboard focus is as visible on buttons as it is on the nav', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openPortal(page);
  await page.locator('.ck-nav-item').first().waitFor({ state: 'attached' });
  const measured = await page.evaluate(() => {
    const read = (selector: string) => {
      const el = document.querySelector<HTMLElement>(selector);
      if (!el) return null;
      el.focus();
      const style = getComputedStyle(el);
      return { selector, width: parseFloat(style.outlineWidth) || 0, style: style.outlineStyle };
    };
    return ['.ck-nav-item', '.ck-btn'].map(read);
  });
  for (const entry of measured) {
    expect(entry, 'element not present to measure').not.toBeNull();
    expect(entry!.style, `${entry!.selector} has no drawn outline`).not.toBe('none');
    // 'auto' is the user-agent ring; the application draws its own.
    expect(entry!.style, `${entry!.selector} falls back to the browser default ring`).toBe('solid');
    expect(entry!.width, `${entry!.selector} focus ring is thinner than the nav's`).toBeGreaterThanOrEqual(2);
  }
});

