import { expect, test, type Page } from '@playwright/test';
import { signIn } from './support/session';

/**
 * Keyboard and target-size gate for the left nav.
 *
 * The same <aside#ck-sidebar-nav> is two different components depending on
 * viewport: a persistent hover-to-expand icon rail at desktop widths, and an
 * off-canvas modal drawer over a backdrop at <=768px. Each of the three
 * behaviours locked in here regressed precisely because a rule written for one
 * of those two modes silently applied to the other.
 *
 * Every assertion is a measurement of the rendered page. Reading the stylesheet
 * cannot answer any of these questions: the rail's behaviour lives in compound
 * `:hover` / `.pinned` / `:has(:focus-visible)` state selectors that contradict
 * each other, and which one wins is a specificity calculation, not a source
 * order you can eyeball.
 */
const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 812 };

async function boot(page: Page, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport);
  await signIn(page);
  // The rail's pinned state persists in localStorage; a pinned rail is already
  // expanded, which would make the collapsed-rail assertions vacuous.
  await page.addInitScript(() => {
    try { localStorage.removeItem('ck_nav_pinned'); } catch { /* ignore */ }
  });
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');
  await page.locator('#ck-sidebar-nav .ck-nav-item').first().waitFor({ state: 'attached' });
}

/** Park the pointer in the far corner so :hover can never be on the sidebar. */
async function parkPointer(page: Page, viewport: { width: number; height: number }) {
  await page.mouse.move(viewport.width - 4, viewport.height - 4);
  await page.waitForTimeout(260); // outlast the rail's width transition
}

async function openDrawer(page: Page) {
  await page.locator('.ck-menu-toggle').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('#ck-sidebar-nav');
    return !!el && el.classList.contains('open') && el.getBoundingClientRect().x > -1;
  }, undefined, { timeout: 10_000 });
  await page.waitForTimeout(350); // outlast --ck-duration-slow
}

/** Where focus is right now, described the way the assertions read. */
function activeDescriptor() {
  const el = document.activeElement as HTMLElement | null;
  const sidebar = document.querySelector('#ck-sidebar-nav');
  if (!el || el === document.body) return { el: 'BODY', inSidebar: false, name: '' };
  const cls = typeof el.className === 'string' ? el.className : '';
  return {
    el: el.tagName.toLowerCase() + (cls ? '.' + cls.trim().split(/\s+/).join('.') : ''),
    inSidebar: !!sidebar && sidebar.contains(el),
    name: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 40),
  };
}

test.describe('nav drawer, mobile', () => {
  test('group headers meet the 24x24 target size', async ({ page }) => {
    await boot(page, MOBILE);
    await openDrawer(page);

    const headers = await page.locator('#ck-sidebar-nav .ck-nav-group-header').evaluateAll(
      (nodes) => nodes.map((el) => {
        const r = el.getBoundingClientRect();
        return {
          title: (el.textContent || '').trim().slice(0, 24),
          w: +r.width.toFixed(2),
          h: +r.height.toFixed(2),
          padding: getComputedStyle(el).padding,
        };
      }),
    );

    expect(headers.length, 'no group headers rendered in the drawer').toBeGreaterThan(0);
    const undersized = headers.filter((h) => h.w < 24 || h.h < 24);
    expect(
      undersized,
      // The collapsed-rail rule zeroes height AND padding and out-scores a
      // plain `.ck-sidebar:not(:hover)` override, so restoring only height with
      // !important leaves the header at its bare text box (~18px).
      `group headers under WCAG 2.5.8's 24x24: ${JSON.stringify(undersized)}`,
    ).toEqual([]);
  });

  test('opening the drawer moves focus into it, traps Tab, and Escape restores focus', async ({ page }) => {
    await boot(page, MOBILE);
    await openDrawer(page);
    await parkPointer(page, MOBILE);

    // 1. focus landed on the close button, i.e. the very first tab stop after
    //    opening is inside the drawer rather than 10 stops down the page.
    expect(await page.evaluate(activeDescriptor)).toMatchObject({
      el: 'button.ck-sb-close',
      inSidebar: true,
    });

    // 2. the page behind the backdrop is inert, so it is neither tabbable nor
    //    clickable while the drawer is open.
    await expect(page.locator('main.ck-main')).toHaveAttribute('inert', '');

    // 3. Tab wraps back to the first control instead of escaping into the page.
    const focusableCount = await page.locator('#ck-sidebar-nav').evaluate((drawer) => Array.from(
      drawer.querySelectorAll<HTMLElement>(
        'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => el.getClientRects().length > 0).length);
    expect(focusableCount).toBeGreaterThan(1);

    const visited: Array<{ el: string; inSidebar: boolean }> = [];
    for (let i = 0; i < focusableCount; i++) {
      await page.keyboard.press('Tab');
      visited.push(await page.evaluate(activeDescriptor));
    }
    expect(visited.every((v) => v.inSidebar), `focus left the drawer: ${JSON.stringify(visited)}`).toBe(true);
    expect(visited[visited.length - 1].el, 'a full Tab cycle did not return to the first control')
      .toBe('button.ck-sb-close');

    // 4. Shift+Tab wraps the other way rather than falling out of the top.
    await page.keyboard.press('Shift+Tab');
    expect(await page.evaluate(activeDescriptor)).toMatchObject({ inSidebar: true });

    // 5. Escape closes it and hands focus back to the control that opened it.
    await page.keyboard.press('Escape');
    await expect(page.locator('#ck-sidebar-nav')).not.toHaveClass(/\bopen\b/);
    await expect(page.locator('main.ck-main')).not.toHaveAttribute('inert', '');
    expect(await page.evaluate(activeDescriptor)).toMatchObject({ el: 'button.ck-menu-toggle' });
  });

  test('the drawer is a modal dialog only while it is open', async ({ page }) => {
    await boot(page, MOBILE);
    const sidebar = page.locator('#ck-sidebar-nav');
    await expect(sidebar).not.toHaveAttribute('role', 'dialog');

    await openDrawer(page);
    await expect(sidebar).toHaveAttribute('role', 'dialog');
    await expect(sidebar).toHaveAttribute('aria-modal', 'true');

    await page.locator('.ck-sb-close').click();
    await expect(sidebar).not.toHaveAttribute('role', 'dialog');
  });
});

test.describe('nav rail, desktop', () => {
  test('is never announced as a modal dialog', async ({ page }) => {
    await boot(page, DESKTOP);
    await parkPointer(page, DESKTOP);
    const sidebar = page.locator('#ck-sidebar-nav');
    // Desktop is a persistent icon rail. role="dialog"/aria-modal here would
    // tell a screen reader the rest of the workspace had gone away.
    await expect(sidebar).not.toHaveAttribute('role', 'dialog');
    await expect(sidebar).not.toHaveAttribute('aria-modal', 'true');
    await expect(page.locator('main.ck-main')).not.toHaveAttribute('inert', '');
  });

  test('the pin toggle is reachable by forward Tab', async ({ page }) => {
    await boot(page, DESKTOP);
    await parkPointer(page, DESKTOP);

    // The pin is invisible until the rail opens, and the rail opens on
    // :has(:focus-visible) — which cannot fire before focus is inside. Hiding
    // it with `display: none` therefore made it unreachable by forward Tab in
    // principle, not just in practice.
    let reachedAt = -1;
    for (let step = 1; step <= 25 && reachedAt < 0; step++) {
      await page.keyboard.press('Tab');
      // :has() invalidation lands on the next frame, not on a forced style
      // recalc, so sampling immediately reports the pre-focus state.
      await page.waitForTimeout(220);
      const here = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement | null;
        if (!el || !el.classList.contains('ck-sb-pin')) return null;
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return {
          w: +r.width.toFixed(2),
          h: +r.height.toFixed(2),
          opacity: cs.opacity,
          ariaPressed: el.getAttribute('aria-pressed'),
        };
      });
      if (here) {
        reachedAt = step;
        // 26x26 already clears WCAG 2.5.8; keeping the box while hidden is what
        // stops it counting as an undersized target in the collapsed rail.
        expect(here.w).toBeGreaterThanOrEqual(24);
        expect(here.h).toBeGreaterThanOrEqual(24);
        // Focusing it is what expands the rail, so by now it must be painted.
        expect(here.opacity).toBe('1');
        expect(here.ariaPressed).toBe('false');
      }
    }
    expect(reachedAt, '.ck-sb-pin was never focused while tabbing forward').toBeGreaterThan(0);

    await expect(page.locator('#ck-sidebar-nav')).toHaveJSProperty('offsetWidth', 248);
  });

  test('the pin toggle is hidden until the rail opens', async ({ page }) => {
    await boot(page, DESKTOP);
    await parkPointer(page, DESKTOP);

    const pin = page.locator('.ck-sb-pin');
    // Deliberate design: it auto-hides. Reachable must not become permanently
    // visible.
    await expect(pin).toHaveCSS('opacity', '0');
    await expect(pin).toHaveCSS('pointer-events', 'none');

    await page.locator('#ck-sidebar-nav').hover({ position: { x: 20, y: 200 } });
    await expect(pin).toHaveCSS('opacity', '1');
    await expect(pin).toHaveCSS('pointer-events', 'auto');
  });
});
