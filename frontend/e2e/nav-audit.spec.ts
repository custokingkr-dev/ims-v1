import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signInAs } from './support/session';

/**
 * Ground truth for the left navigation, measured in a real browser.
 *
 * This is a REPORT, not a gate. It passes as long as it can actually reach the
 * nav; the numbers it collects are written to e2e/.artifacts/nav-audit.json and
 * printed as a summary table. It fails loudly only when the harness cannot see
 * the sidebar at all, so a broken fixture can never masquerade as a clean audit.
 *
 * Why measured rather than read off the source: the rail's behaviour is
 * expressed entirely in compound CSS state selectors
 * (`:hover`, `.pinned`, `:has(:focus-visible)`, `@media (max-width: 768px)`),
 * several of which contradict each other. Reading the stylesheet tells you what
 * was intended; only getComputedStyle tells you what ships.
 *
 * States measured, per role:
 *   A  1280x800, collapsed 64px icon rail (pointer parked off the sidebar)
 *   B  1280x800, expanded 248px (pinned via the real .ck-sb-pin affordance)
 *   C  375x812, off-canvas drawer opened via .ck-menu-toggle
 *   D  320x720, WCAG 1.4.10 reflow floor (drawer closed and open)
 */

const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 812 };
const REFLOW = { width: 320, height: 720 };

/**
 * playwright.config.ts sets `reuseExistingServer: !CI`, so a dev server already
 * listening on the configured port is adopted silently — even one started from
 * a different git worktree. That is not hypothetical: this repo runs several
 * agent worktrees side by side, and an early run of this audit measured another
 * branch's sidebar end to end without a single warning.
 *
 * Set NAV_AUDIT_BASE_URL to point at a server you started yourself. Either way
 * assertServingThisWorktree() below refuses to report numbers for code that is
 * not the code in this checkout.
 */
const BASE_URL = process.env.NAV_AUDIT_BASE_URL ?? '';
const url = (p: string) => (BASE_URL ? new URL(p, BASE_URL).toString() : p);

/** Every text-bearing element in the sidebar, per the brief. */
const TEXT_SELECTORS = [
  '.ck-nav-group-title',
  '.ck-nav-label',
  '.ck-nav-badge',
  '.ck-sb-logo',
  '.ck-school-name',
  '.ck-school-meta',
  '.ck-sb-school-badge',
  '.ck-user-name',
  '.ck-user-meta',
];

/**
 * Ancestors that supply an inherited font-size to the elements above. Measured
 * for their authored declaration only - they are not counted as nav text.
 */
const AUTHORED_EXTRA_SELECTORS = ['.ck-nav-group-header', '.ck-nav-item', 'body'];

const MEASURE_SELECTORS = { text: TEXT_SELECTORS, authoredExtra: AUTHORED_EXTRA_SELECTORS };

/**
 * A real school ADMIN. Deliberately WITHOUT `platform:admin`: that single
 * permission makes isPlatformAdmin true and swaps in the SUPERADMIN nav, so the
 * default e2e session has never actually rendered the ADMIN nav.
 */
const ADMIN_PERMISSIONS = [
  'school:read', 'school:update',
  'student:read', 'student:create', 'student:import', 'student:photo-import', 'student:export',
  'attendance:read', 'timetable:read', 'staff:read',
  'fee:read', 'payment:read', 'fee_structure:read',
  'order:read', 'plan:read',
  'firefighting:read', 'firefighting:create', 'firefighting:approve',
];

/** Every legacy module code, so ERP + SUPPLY_OS are both derived as active. */
const ALL_MODULE_CODES = [
  'STUDENTS', 'ATTENDANCE', 'FEES', 'INVOICES', 'PAYMENTS', 'ORDERS', 'FIREFIGHTING', 'REPORTS',
];

const ROLES = [
  {
    id: 'ADMIN',
    note: 'school admin, ERP + Supply OS both entitled (worst case for item count)',
    session: {
      role: 'ADMIN',
      fullName: 'Asha Admin',
      permissions: ADMIN_PERMISSIONS,
      modules: ALL_MODULE_CODES,
    },
  },
  {
    id: 'SUPERADMIN',
    note: 'platform admin (worst case for group count; also what plain signIn() renders)',
    session: {
      role: 'SUPERADMIN',
      fullName: 'Priya Platform',
      permissions: ['platform:admin'],
      modules: [],
      pendingInvoices: 7,
    },
  },
];

// ── in-page measurement ─────────────────────────────────────────────────────
// Serialised into the browser as one function so a state is captured from a
// single consistent layout pass.

function measureInPage(selectors: { text: string[]; authoredExtra: string[] }) {
  const textSelectors = selectors.text;
  const sidebar = document.querySelector('#ck-sidebar-nav') as HTMLElement | null;
  if (!sidebar) return { reachable: false } as any;

  const cls = (el: Element) => {
    const c = (el as HTMLElement).className;
    return typeof c === 'string' ? c : el.getAttribute('class') || '';
  };
  const describe = (el: Element) => {
    const c = cls(el).trim().split(/\s+/).filter(Boolean).slice(0, 3).join('.');
    return el.tagName.toLowerCase() + (c ? '.' + c : '');
  };
  const rectOf = (el: Element) => {
    const r = el.getBoundingClientRect();
    return {
      x: +r.x.toFixed(2), y: +r.y.toFixed(2),
      w: +r.width.toFixed(2), h: +r.height.toFixed(2),
    };
  };

  // ── 1. type ──────────────────────────────────────────────────────────────
  const type: any[] = [];
  for (const sel of textSelectors) {
    const nodes = Array.from(sidebar.querySelectorAll(sel));
    for (const el of nodes) {
      const cs = getComputedStyle(el);
      type.push({
        selector: sel,
        text: (el.textContent || '').trim().slice(0, 40),
        fontSizePx: parseFloat(cs.fontSize),
        fontWeight: cs.fontWeight,
        lineHeight: cs.lineHeight,
        letterSpacing: cs.letterSpacing,
        textTransform: cs.textTransform,
        color: cs.color,
        opacity: cs.opacity,
        visibility: cs.visibility,
        display: cs.display,
        rect: rectOf(el),
        /** clipped = present in the a11y tree but painted at zero size / zero alpha */
        clipped: cs.opacity === '0' || el.getBoundingClientRect().width === 0
          || el.getBoundingClientRect().height === 0,
      });
    }
    if (nodes.length === 0) type.push({ selector: sel, absent: true });
  }

  // ── authored units, read from the live CSSOM (not from source text) ───────
  const authored: any[] = [];
  const walk = (rules: CSSRuleList | undefined, condition: string | null) => {
    for (const rule of Array.from(rules || []) as any[]) {
      if (!rule.selectorText && rule.cssRules) {
        walk(rule.cssRules, rule.conditionText || rule.media?.mediaText || condition);
        continue;
      }
      if (!rule.selectorText || !rule.style) continue;
      // A `font:` shorthand does not always expose the font-size longhand
      // through the CSSOM, so check the shorthand too or those rules read as
      // "nothing authored" when they are in fact hard-coded px.
      const shorthand = rule.style.getPropertyValue('font');
      const declared = rule.style.getPropertyValue('font-size')
        || (shorthand ? `${shorthand.trim()} (font shorthand)` : '');
      if (!declared) continue;
      const parts = String(rule.selectorText).split(',').map((s: string) => s.trim());
      // authoredExtra carries the ancestors that SUPPLY an inherited size
      // (.ck-nav-group-header, .ck-nav-item, body). Without them "inherited"
      // is a dead end and the px-vs-rem question cannot be answered.
      for (const sel of [...textSelectors, ...selectors.authoredExtra]) {
        // exact match, or the selector is the last (keyed) compound in the rule
        const hit = parts.some((p: string) => p === sel || p.endsWith(' ' + sel));
        if (hit) authored.push({ selector: sel, rule: rule.selectorText, declared, media: condition });
      }
    }
  };
  for (const sheet of Array.from(document.styleSheets)) {
    try { walk((sheet as CSSStyleSheet).cssRules, null); } catch { /* cross-origin */ }
  }
  const rootStyle = getComputedStyle(document.documentElement);
  const typeTokens: Record<string, string> = {};
  for (const name of ['--ck-text-xs', '--ck-text-sm', '--ck-text-base', '--ck-text-md', '--ck-text-lg']) {
    typeTokens[name] = rootStyle.getPropertyValue(name).trim();
  }

  // ── 2. target size (WCAG 2.5.8) ──────────────────────────────────────────
  const controls = Array.from(
    sidebar.querySelectorAll('button, a, [role="button"], input, select'),
  ).map((el) => {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    // Layout size alone does not prove a control is operable: an ancestor with
    // height:0;overflow:hidden clips both paint and hit-testing while leaving
    // the child's own rect intact. Ask the browser who actually owns the pixel.
    // elementFromPoint also returns null for coordinates outside the viewport,
    // so separate "covered by something else" from "below the fold".
    const cx = r.x + r.width / 2;
    const cy = r.y + r.height / 2;
    const inViewport = r.width > 0 && r.height > 0
      && cx >= 0 && cx <= window.innerWidth && cy >= 0 && cy <= window.innerHeight;
    const hit = inViewport ? document.elementFromPoint(cx, cy) : null;
    return {
      el: describe(el),
      label: el.getAttribute('aria-label'),
      text: (el.textContent || '').trim().slice(0, 30),
      rect: rectOf(el),
      display: cs.display,
      visibility: cs.visibility,
      opacity: cs.opacity,
      rendered: cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0,
      inViewport,
      hitTestOwner: hit ? describe(hit) : null,
      /** operable right now, without scrolling the sidebar */
      clickable: !!hit && (hit === el || el.contains(hit)),
      /** on screen, but some other element owns its centre pixel */
      occluded: inViewport && !!hit && hit !== el && !el.contains(hit),
      /** laid out, but off screen until the sidebar is scrolled */
      belowTheFold: r.width > 0 && r.height > 0 && !inViewport,
      undersized: r.width > 0 && r.height > 0 && (r.width < 24 || r.height < 24),
    };
  });
  const menuToggle = document.querySelector('.ck-menu-toggle');
  const menuToggleInfo = menuToggle
    ? (() => {
      const cs = getComputedStyle(menuToggle);
      return {
        el: describe(menuToggle),
        label: menuToggle.getAttribute('aria-label'),
        rect: rectOf(menuToggle),
        display: cs.display,
        rendered: cs.display !== 'none',
      };
    })()
    : null;

  // ── 4. accessible names ──────────────────────────────────────────────────
  const axe: any = (window as any).axe;
  let accSource = 'fallback(aria-label||textContent)';
  let setupOk = false;
  if (axe && axe.commons && axe.commons.text) {
    try { axe.teardown(); } catch { /* not set up */ }
    try { axe.setup(document.documentElement); setupOk = true; accSource = 'axe-core accessibleText'; } catch { /* ignore */ }
  }
  const accNameOf = (el: Element) => {
    if (setupOk) {
      try { return String(axe.commons.text.accessibleText(el) ?? '').trim(); } catch { /* fall through */ }
    }
    return (el.getAttribute('aria-label') || el.textContent || '').trim();
  };
  const named = Array.from(
    sidebar.querySelectorAll('button, a, [role="button"], .ck-nav-item'),
  ).map((el) => {
    const cs = getComputedStyle(el);
    return {
      el: describe(el),
      accessibleName: accNameOf(el),
      ariaLabel: el.getAttribute('aria-label'),
      title: el.getAttribute('title'),
      textContent: (el.textContent || '').trim().slice(0, 40),
      ariaHidden: el.closest('[aria-hidden="true"]') !== null,
      renderedDisplay: cs.display,
      rect: rectOf(el),
    };
  });
  const labelExposure = Array.from(sidebar.querySelectorAll('.ck-nav-item')).map((item) => {
    const label = item.querySelector('.ck-nav-label');
    const lcs = label ? getComputedStyle(label) : null;
    return {
      accessibleName: accNameOf(item),
      labelText: label ? (label.textContent || '').trim() : null,
      labelOpacity: lcs ? lcs.opacity : null,
      labelWidth: label ? +label.getBoundingClientRect().width.toFixed(2) : null,
      labelDisplay: lcs ? lcs.display : null,
    };
  });
  if (setupOk) { try { axe.teardown(); } catch { /* ignore */ } }

  // ── 5. rail geometry ─────────────────────────────────────────────────────
  const sbRect = sidebar.getBoundingClientRect();
  const railCentreX = +(sbRect.x + sbRect.width / 2).toFixed(2);
  // The rail paints a 1px border-right, so the icons' available box is narrower
  // than the border box. Report the offset against both so "centred" is not an
  // argument about which box you measured.
  const railContentCentreX = +(sbRect.x + sidebar.clientWidth / 2).toFixed(2);
  const icons = Array.from(sidebar.querySelectorAll('.ck-nav-item')).map((item) => {
    const svg = item.querySelector('svg');
    const span = item.querySelector(':scope > span[aria-hidden]');
    const glyph = (svg || span) as Element | null;
    if (!glyph) {
      return { item: (item.getAttribute('aria-label') || '').trim(), glyph: 'NONE', offsetX: null };
    }
    const g = glyph.getBoundingClientRect();
    const centre = g.x + g.width / 2;
    return {
      item: (item.getAttribute('aria-label') || '').trim(),
      glyph: glyph.tagName.toLowerCase(),
      glyphSize: { w: +g.width.toFixed(2), h: +g.height.toFixed(2) },
      centreX: +centre.toFixed(2),
      offsetX: +(centre - railCentreX).toFixed(2),
      offsetXContentBox: +(centre - railContentCentreX).toFixed(2),
    };
  });

  // ── 6. overflow ──────────────────────────────────────────────────────────
  const nav = sidebar.querySelector('.ck-nav') as HTMLElement | null;
  const items = Array.from(sidebar.querySelectorAll('.ck-nav-item'));
  const renderedItems = items.filter((el) => getComputedStyle(el).display !== 'none');
  const sbBox = sidebar.getBoundingClientRect();
  const visibleWithoutScroll = renderedItems.filter((el) => {
    const r = el.getBoundingClientRect();
    return r.top >= sbBox.top - 0.5 && r.bottom <= sbBox.bottom + 0.5
      && r.top >= -0.5 && r.bottom <= window.innerHeight + 0.5;
  }).length;
  const overflow = {
    sidebar: {
      scrollHeight: sidebar.scrollHeight,
      clientHeight: sidebar.clientHeight,
      scrolls: sidebar.scrollHeight > sidebar.clientHeight + 1,
      overflowY: getComputedStyle(sidebar).overflowY,
      width: +sbRect.width.toFixed(2),
    },
    nav: nav
      ? {
        scrollHeight: nav.scrollHeight,
        clientHeight: nav.clientHeight,
        scrolls: nav.scrollHeight > nav.clientHeight + 1,
        overflowY: getComputedStyle(nav).overflowY,
      }
      : null,
    groupsRendered: sidebar.querySelectorAll('.ck-nav-group').length,
    itemsInDom: items.length,
    itemsRendered: renderedItems.length,
    itemsVisibleWithoutScroll: visibleWithoutScroll,
  };

  // ── 7. document reflow ───────────────────────────────────────────────────
  const docWidth = document.documentElement.clientWidth;
  const offenders: string[] = [];
  for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    if (r.right > docWidth + 1) offenders.push(`${describe(el)} right=${Math.round(r.right)}`);
  }
  const reflow = {
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: docWidth,
    bleedPx: document.documentElement.scrollWidth - docWidth,
    offenders: Array.from(new Set(offenders)).slice(0, 10),
  };

  return {
    reachable: true,
    sidebar: {
      classList: Array.from(sidebar.classList),
      rect: rectOf(sidebar),
      matchesHover: sidebar.matches(':hover'),
      matchesPinned: sidebar.classList.contains('pinned'),
      matchesFocusVisible: (() => { try { return sidebar.matches(':has(:focus-visible)'); } catch { return null; } })(),
    },
    type, authored, typeTokens,
    controls, menuToggle: menuToggleInfo,
    accessibleNames: { source: accSource, controls: named, labelExposure },
    railCentreX,
    railContentCentreX,
    icons,
    overflow,
    reflow,
  };
}

// ── driver helpers ──────────────────────────────────────────────────────────

/**
 * Refuse to report numbers for somebody else's checkout.
 *
 * Reads the authored `width` of the bare `.ck-sidebar` rule off disk and
 * compares it with the same declaration in the served page's CSSOM. Any drift
 * means the dev server belongs to a different tree.
 */
async function assertServingThisWorktree(page: Page, testDir: string) {
  const cssPath = path.resolve(testDir, '../src/styles.css');
  const source = fs.readFileSync(cssPath, 'utf8');
  const block = source.match(/\n\.ck-sidebar\s*\{([\s\S]*?)\}/);
  const onDisk = block?.[1].match(/(?:^|;|\n)\s*width\s*:\s*([^;]+);/)?.[1].trim() ?? null;
  expect(onDisk, `could not read the authored .ck-sidebar width from ${cssPath}`).toBeTruthy();

  const served = await page.evaluate(() => {
    let found: string | null = null;
    const walk = (rules: CSSRuleList | undefined, conditional: boolean) => {
      for (const rule of Array.from(rules || []) as any[]) {
        if (!rule.selectorText && rule.cssRules) { walk(rule.cssRules, true); continue; }
        if (conditional || !rule.selectorText || !rule.style) continue;
        if (String(rule.selectorText).trim() !== '.ck-sidebar') continue;
        const w = rule.style.getPropertyValue('width');
        if (w) found = w.trim();
      }
    };
    for (const sheet of Array.from(document.styleSheets)) {
      try { walk((sheet as CSSStyleSheet).cssRules, false); } catch { /* cross-origin */ }
    }
    return found;
  });

  expect(
    served,
    `The dev server is serving a DIFFERENT tree than this checkout.\n`
    + `  authored in ${cssPath}: .ck-sidebar { width: ${onDisk} }\n`
    + `  served by ${page.url()}:  .ck-sidebar { width: ${served} }\n`
    + `  playwright.config.ts reuses any server already on the configured port.\n`
    + `  Start a server from THIS worktree and re-run with NAV_AUDIT_BASE_URL set to it.`,
  ).toBe(onDisk);

  return { authoredSidebarWidth: onDisk, servedSidebarWidth: served, baseUrl: page.url() };
}

async function bootWorkspace(page: Page, session: any, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport);
  await signInAs(page, session);
  // The rail's pinned state and per-group accordion state both persist in
  // localStorage. Clear them on every navigation so every state starts from the
  // documented default (collapsed rail, all groups open).
  await page.addInitScript(() => {
    try {
      localStorage.removeItem('ck_nav_pinned');
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith('ck_nav_groups:')) localStorage.removeItem(key);
      }
    } catch { /* ignore */ }
  });
  await page.goto(url('/dashboard'));
  await page.waitForLoadState('networkidle');
  await page.locator('#ck-sidebar-nav').waitFor({ state: 'attached', timeout: 15_000 });
  await page.locator('#ck-sidebar-nav .ck-nav-item').first().waitFor({ state: 'attached', timeout: 15_000 });
}

/** Park the pointer in the far corner so :hover cannot be on the sidebar. */
async function parkPointer(page: Page, viewport: { width: number; height: number }) {
  await page.mouse.move(viewport.width - 4, viewport.height - 4);
  await page.waitForTimeout(260); // outlast the .18s width transition
}

async function injectAxe(page: Page, axePath: string) {
  await page.addScriptTag({ path: axePath });
}

async function runAxe(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .include('#ck-sidebar-nav')
    .analyze();
  const shape = (list: any[]) => list.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n: any) => {
      const check = [...(n.any || []), ...(n.all || []), ...(n.none || [])][0];
      return {
        target: n.target,
        html: String(n.html || '').replace(/\s+/g, ' ').slice(0, 140),
        message: String(check?.message || '').replace(/\s+/g, ' ').slice(0, 180),
        contrastRatio: check?.data?.contrastRatio ?? null,
        fgColor: check?.data?.fgColor ?? null,
        bgColor: check?.data?.bgColor ?? null,
        fontSize: check?.data?.fontSize ?? null,
        fontWeight: check?.data?.fontWeight ?? null,
        expectedContrastRatio: check?.data?.expectedContrastRatio ?? null,
      };
    }),
  }));
  // Passing nodes carry the measured ratio too. A clean run is far more
  // convincing when it shows the actual numbers rather than just "0 violations".
  const contrastPasses = (results.passes.find((p) => p.id === 'color-contrast')?.nodes ?? [])
    .map((n: any) => {
      const check = (n.any || [])[0];
      return {
        target: n.target,
        html: String(n.html || '').replace(/\s+/g, ' ').slice(0, 90),
        contrastRatio: check?.data?.contrastRatio ?? null,
        expectedContrastRatio: check?.data?.expectedContrastRatio ?? null,
        fgColor: check?.data?.fgColor ?? null,
        bgColor: check?.data?.bgColor ?? null,
        fontSize: check?.data?.fontSize ?? null,
        fontWeight: check?.data?.fontWeight ?? null,
      };
    });
  return {
    violations: shape(results.violations),
    incomplete: shape(results.incomplete),
    contrastPasses,
    rulesRun: results.passes.length + results.violations.length + results.incomplete.length,
    ruleCounts: {
      violations: results.violations.length,
      incomplete: results.incomplete.length,
      contrastNodesEvaluated: contrastPasses.length,
    },
  };
}

/**
 * Tab from wherever focus currently is and record where it actually lands.
 *
 * The starting point is recorded rather than forced. blur() does NOT reset
 * Chromium's sequential focus navigation starting point, so "resetting" focus
 * before a tab run silently lies about the order. For the desktop states the
 * run begins on a freshly loaded page (focus on body = top of document); for
 * the drawer it begins wherever clicking .ck-menu-toggle left it, which is
 * exactly what a keyboard user gets.
 */
async function tabOrder(page: Page, viewport: { width: number; height: number }, maxTabs = 45) {
  await parkPointer(page, viewport);
  const startingPoint = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return 'body (top of document)';
    const c = typeof el.className === 'string' ? el.className : el.getAttribute('class') || '';
    return el.tagName.toLowerCase() + (c ? '.' + c.trim().split(/\s+/)[0] : '')
      + ` [${(el.getAttribute('aria-label') || (el.textContent || '').trim()).slice(0, 30)}]`;
  });

  const steps: any[] = [];
  for (let i = 0; i < maxTabs; i++) {
    await page.keyboard.press('Tab');
    // The rail expands on :has(:focus-visible) through a .18s width transition,
    // and Chromium applies the :has() invalidation on the next frame rather than
    // on a forced synchronous recalc. Reading without this settle reports the
    // pre-focus 64px rail at every stop and makes a working expansion look dead.
    await page.waitForTimeout(220);
    const step = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      const sb = document.querySelector('#ck-sidebar-nav') as HTMLElement | null;
      const cls = (n: Element) => {
        const c = (n as HTMLElement).className;
        return typeof c === 'string' ? c : n.getAttribute('class') || '';
      };
      const hasFocusVisible = (() => {
        try { return sb ? sb.matches(':has(:focus-visible)') : null; } catch { return null; }
      })();
      if (!el || el === document.body || el === document.documentElement) {
        return {
          el: 'BODY (document)', inSidebar: false,
          sidebarWidth: sb ? +sb.getBoundingClientRect().width.toFixed(1) : null,
          sidebarHasFocusVisible: hasFocusVisible,
        };
      }
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const c = cls(el).trim().split(/\s+/).filter(Boolean).slice(0, 2).join('.');
      return {
        el: el.tagName.toLowerCase() + (c ? '.' + c : ''),
        name: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 34),
        inSidebar: !!sb && sb.contains(el),
        rect: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
        opacity: cs.opacity,
        visibility: cs.visibility,
        display: cs.display,
        focusVisible: (() => { try { return el.matches(':focus-visible'); } catch { return null; } })(),
        offscreen: r.right <= 0 || r.bottom <= 0 || r.left >= window.innerWidth,
        zeroSize: r.width === 0 || r.height === 0,
        sidebarWidth: sb ? +sb.getBoundingClientRect().width.toFixed(1) : null,
        sidebarHasFocusVisible: hasFocusVisible,
      };
    });
    steps.push(step);
    // Once focus has entered and then left the sidebar, we have the whole run.
    const entered = steps.some((s) => s.inSidebar);
    if (entered && !step.inSidebar && steps.filter((s) => !s.inSidebar).length > 2) break;
  }

  const firstInside = steps.findIndex((s) => s.inSidebar);
  const inside = steps.filter((s) => s.inSidebar);
  const widthsWhileFocused = Array.from(new Set(inside.map((s) => s.sidebarWidth)));
  const focusableWhileHidden = inside.filter((s) => s.zeroSize || s.opacity === '0' || s.visibility === 'hidden' || s.offscreen);
  const escaped = steps.some((s, i) => i > (firstInside < 0 ? 0 : firstInside) && !s.inSidebar);
  return {
    startingPoint,
    steps,
    stopsInsideSidebar: inside.length,
    firstStopIndex: firstInside,
    reachedPinButton: inside.some((s) => String(s.el).includes('ck-sb-pin')),
    reachedCloseButton: inside.some((s) => String(s.el).includes('ck-sb-close')),
    focusVisibleExpansionFired: inside.some((s) => s.sidebarHasFocusVisible === true),
    widthAtFirstStop: firstInside >= 0 ? steps[firstInside].sidebarWidth : null,
    widthsWhileFocused,
    widthAfterLeaving: steps.filter((s, i) => firstInside >= 0 && i > firstInside && !s.inSidebar)[0]?.sidebarWidth ?? null,
    focusableWhileHidden: focusableWhileHidden.map((s) => ({ el: s.el, name: s.name, rect: s.rect, opacity: s.opacity, offscreen: s.offscreen })),
    focusTrapSuspected: firstInside >= 0 && !escaped,
  };
}

// ── the audit ───────────────────────────────────────────────────────────────

test('measure the left nav', async ({ page }, testInfo) => {
  test.setTimeout(300_000);

  const axePath = path.resolve(testInfo.project.testDir, '../node_modules/axe-core/axe.min.js');
  expect(fs.existsSync(axePath), `axe-core bundle not found at ${axePath}`).toBe(true);

  const report: any = {
    generatedAt: new Date().toISOString(),
    commit: process.env.GITHUB_SHA ?? null,
    viewports: { desktop: DESKTOP, mobile: MOBILE, reflow: REFLOW },
    roles: {},
  };

  for (const role of ROLES) {
    const roleReport: any = { note: role.note, session: role.session, states: {} };

    // ── A: collapsed 64px rail ────────────────────────────────────────────
    await bootWorkspace(page, role.session, DESKTOP);
    report.servingTree = await assertServingThisWorktree(page, testInfo.project.testDir);
    await parkPointer(page, DESKTOP);
    const itemCountA = await page.locator('#ck-sidebar-nav .ck-nav-item').count();
    expect(itemCountA, `${role.id}: harness reached zero nav items in the collapsed rail`).toBeGreaterThan(0);
    await injectAxe(page, axePath);
    roleReport.states.A_collapsed_rail = {
      label: 'desktop 1280x800, collapsed 64px rail (pointer parked bottom-right)',
      ...(await page.evaluate(measureInPage, MEASURE_SELECTORS)),
      axe: await runAxe(page),
      ariaSnapshot: await page.locator('#ck-sidebar-nav').ariaSnapshot(),
    };

    // ── B: expanded ───────────────────────────────────────────────────────
    // Prove which affordance actually opens it rather than assuming.
    const expansion: any = { attempted: [] };
    await page.locator('#ck-sidebar-nav').hover({ position: { x: 20, y: 200 } });
    await page.waitForTimeout(260);
    expansion.attempted.push({
      mechanism: 'hover .ck-sidebar',
      width: await page.locator('#ck-sidebar-nav').evaluate((el) => +el.getBoundingClientRect().width.toFixed(1)),
      pinButtonVisible: await page.locator('.ck-sb-pin').isVisible(),
    });
    let mechanismUsed = 'hover';
    if (await page.locator('.ck-sb-pin').isVisible()) {
      await page.locator('.ck-sb-pin').click();
      await parkPointer(page, DESKTOP);
      const pinned = await page.locator('#ck-sidebar-nav').evaluate((el) => el.classList.contains('pinned'));
      const width = await page.locator('#ck-sidebar-nav').evaluate((el) => +el.getBoundingClientRect().width.toFixed(1));
      expansion.attempted.push({ mechanism: 'click .ck-sb-pin then park pointer', pinnedClass: pinned, width });
      if (pinned && width > 200) mechanismUsed = 'pinned (clicked .ck-sb-pin, pointer parked off-sidebar)';
      else { await page.locator('#ck-sidebar-nav').hover({ position: { x: 20, y: 200 } }); await page.waitForTimeout(260); }
    } else {
      // pin never became clickable — fall back to holding hover
      await page.locator('#ck-sidebar-nav').hover({ position: { x: 20, y: 200 } });
      await page.waitForTimeout(260);
      mechanismUsed = 'hover held on .ck-sidebar (.ck-sb-pin was not visible)';
    }
    expansion.used = mechanismUsed;

    const itemCountB = await page.locator('#ck-sidebar-nav .ck-nav-item').count();
    expect(itemCountB, `${role.id}: harness reached zero nav items when expanded`).toBeGreaterThan(0);
    await injectAxe(page, axePath);
    roleReport.states.B_expanded = {
      label: 'desktop 1280x800, expanded 248px',
      expansion,
      ...(await page.evaluate(measureInPage, MEASURE_SELECTORS)),
      axe: await runAxe(page),
      ariaSnapshot: await page.locator('#ck-sidebar-nav').ariaSnapshot(),
    };

    // ── badge probe: .ck-nav-badge only exists while the invoices panel is open
    const invoiceItem = page.locator('#ck-sidebar-nav .ck-nav-item[aria-label="Invoices"]');
    if (await invoiceItem.count()) {
      await invoiceItem.click();
      await page.locator('#ck-sidebar-nav').hover({ position: { x: 20, y: 200 } });
      const badge = page.locator('#ck-sidebar-nav .ck-nav-badge');
      const appeared = await badge.count().then(async (c) => {
        if (c) return true;
        try { await badge.waitFor({ state: 'attached', timeout: 4000 }); return true; } catch { return false; }
      });
      roleReport.states.B_expanded.badgeProbe = appeared
        ? {
          rendered: true,
          ...(await badge.evaluate((el) => {
            const cs = getComputedStyle(el);
            const r = el.getBoundingClientRect();
            return {
              text: (el.textContent || '').trim(),
              fontSizePx: parseFloat(cs.fontSize),
              color: cs.color,
              background: cs.backgroundColor,
              rect: { w: +r.width.toFixed(2), h: +r.height.toFixed(2) },
            };
          })),
        }
        : { rendered: false, why: 'requires the sa-invoices panel to be mounted AND stats.pending > 0' };
    } else {
      roleReport.states.B_expanded.badgeProbe = { rendered: false, why: 'no Invoices nav item for this role' };
    }

    // ── 8: focus order (desktop, unpinned so :has(:focus-visible) is testable)
    await bootWorkspace(page, role.session, DESKTOP);
    roleReport.states.A_collapsed_rail.focusOrder = await tabOrder(page, DESKTOP);

    // ── C: mobile drawer ──────────────────────────────────────────────────
    await bootWorkspace(page, role.session, MOBILE);
    const toggle = page.locator('.ck-menu-toggle');
    await expect(toggle, `${role.id}: .ck-menu-toggle is not visible at 375px`).toBeVisible();
    const closedRect = await page.locator('#ck-sidebar-nav').evaluate((el) => {
      const r = el.getBoundingClientRect();
      return { x: +r.x.toFixed(1), w: +r.width.toFixed(1) };
    });
    await toggle.click();
    await page.waitForFunction(() => {
      const el = document.querySelector('#ck-sidebar-nav');
      return !!el && el.classList.contains('open') && el.getBoundingClientRect().x > -1;
    }, undefined, { timeout: 10_000 });
    await page.waitForTimeout(350); // outlast --ck-duration-slow
    await parkPointer(page, MOBILE);
    const itemCountC = await page.locator('#ck-sidebar-nav .ck-nav-item').count();
    expect(itemCountC, `${role.id}: harness reached zero nav items in the drawer`).toBeGreaterThan(0);
    await injectAxe(page, axePath);
    roleReport.states.C_mobile_drawer = {
      label: 'mobile 375x812, drawer opened via .ck-menu-toggle',
      drawerClosedRect: closedRect,
      backdropVisible: await page.locator('.ck-sidebar-backdrop').isVisible(),
      ...(await page.evaluate(measureInPage, MEASURE_SELECTORS)),
      axe: await runAxe(page),
      ariaSnapshot: await page.locator('#ck-sidebar-nav').ariaSnapshot(),
      focusOrder: await tabOrder(page, MOBILE),
    };

    // ── D: 320px reflow floor ─────────────────────────────────────────────
    await bootWorkspace(page, role.session, REFLOW);
    await parkPointer(page, REFLOW);
    await injectAxe(page, axePath);
    const closed320 = await page.evaluate(measureInPage, MEASURE_SELECTORS);
    await page.locator('.ck-menu-toggle').click();
    await page.waitForFunction(() => {
      const el = document.querySelector('#ck-sidebar-nav');
      return !!el && el.classList.contains('open') && el.getBoundingClientRect().x > -1;
    }, undefined, { timeout: 10_000 });
    await page.waitForTimeout(350);
    const open320 = await page.evaluate(measureInPage, MEASURE_SELECTORS);
    expect(open320.reachable, `${role.id}: sidebar unreachable at 320px`).toBe(true);
    roleReport.states.D_reflow_320 = {
      label: '320x720, WCAG 1.4.10 reflow floor',
      drawerClosed: closed320,
      drawerOpen: open320,
    };

    report.roles[role.id] = roleReport;
  }

  // ── write the machine-readable artefact ───────────────────────────────────
  const outDir = path.join(testInfo.project.testDir, '.artifacts');
  fs.mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, 'nav-audit.json');
  fs.writeFileSync(outFile, JSON.stringify(report, null, 2), 'utf8');

  printSummary(report, outFile);

  // The audit is a report. The only hard failure is not reaching the nav.
  for (const [roleId, r] of Object.entries<any>(report.roles)) {
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const reachable = s.reachable ?? s.drawerOpen?.reachable;
      expect(reachable, `${roleId}/${stateId}: sidebar not reachable`).toBe(true);
    }
  }
});

// ── stdout summary ──────────────────────────────────────────────────────────

function printSummary(report: any, outFile: string) {
  const L: string[] = [];
  const pad = (s: any, n: number) => String(s).padEnd(n).slice(0, n);
  const rule = (n = 104) => '-'.repeat(n);

  L.push('');
  L.push('='.repeat(104));
  L.push('LEFT NAV - MEASURED GROUND TRUTH   ' + report.generatedAt);
  L.push(`served by ${report.servingTree?.baseUrl} :: .ck-sidebar{width:${report.servingTree?.servedSidebarWidth}} `
    + `matches this checkout (${report.servingTree?.authoredSidebarWidth})`);
  L.push('='.repeat(104));

  for (const [roleId, r] of Object.entries<any>(report.roles)) {
    L.push('');
    L.push(`### ROLE ${roleId}  (${r.note})`);

    // 1. type
    L.push('');
    L.push('TYPE (computed px; * = under the 12px floor)');
    L.push(pad('state', 22) + pad('selector', 24) + pad('px', 7) + pad('weight', 8) + pad('n', 4) + 'authored');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const m = s.reachable ? s : s.drawerOpen;
      if (!m?.type) continue;
      const grouped = new Map<string, any[]>();
      for (const t of m.type) {
        if (t.absent) continue;
        const key = `${t.selector}|${t.fontSizePx}|${t.fontWeight}`;
        if (!grouped.has(key)) grouped.set(key, []);
        grouped.get(key)!.push(t);
      }
      for (const [key, group] of grouped) {
        const [selector, px, weight] = key.split('|');
        const authored = (m.authored || [])
          .filter((a: any) => a.selector === selector)
          .map((a: any) => `${a.declared}${a.media ? ` @${a.media}` : ''}`);
        const flag = Number(px) < 12 ? '*' : ' ';
        L.push(
          pad(stateId, 22) + pad(selector, 24) + pad(px + flag, 7) + pad(weight, 8)
          + pad(group.length, 4) + (authored.length ? Array.from(new Set(authored)).join(' | ') : 'inherited (none authored)'),
        );
      }
      const absent = m.type.filter((t: any) => t.absent).map((t: any) => t.selector);
      if (absent.length) L.push(pad(stateId, 22) + pad('(not rendered)', 24) + absent.join(', '));
    }
    const firstState: any = Object.values<any>(r.states)[0];
    const inheritedFrom = (firstState?.authored ?? [])
      .filter((a: any) => ['.ck-nav-group-header', '.ck-nav-item', 'body'].includes(a.selector))
      .map((a: any) => `${a.selector} { font-size: ${a.declared} }${a.media ? ` @${a.media}` : ''}`);
    if (inheritedFrom.length) {
      L.push('  inherited sizes come from: ' + Array.from(new Set(inheritedFrom)).join('  |  '));
    }
    if (firstState?.typeTokens) {
      L.push('  type tokens: ' + Object.entries(firstState.typeTokens).map(([k, v]) => `${k}=${v}`).join('  '));
    }

    // 2. targets
    L.push('');
    L.push('TARGET SIZE (WCAG 2.5.8 - 24x24 CSS px)');
    L.push(pad('state', 22) + pad('in DOM', 8) + pad('laid out', 10) + pad('clickable', 11) + pad('under 24', 10) + 'offenders (w x h)');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const m = s.reachable ? s : s.drawerOpen;
      if (!m?.controls) continue;
      const rendered = m.controls.filter((c: any) => c.rendered);
      const clickable = m.controls.filter((c: any) => c.clickable);
      const bad = rendered.filter((c: any) => c.undersized);
      L.push(
        pad(stateId, 22) + pad(m.controls.length, 8) + pad(rendered.length, 10) + pad(clickable.length, 11) + pad(bad.length, 10)
        + bad.map((c: any) => `${c.label || c.text || c.el} ${c.rect.w}x${c.rect.h}`).slice(0, 4).join('; '),
      );
      const below = rendered.filter((c: any) => c.belowTheFold);
      const occluded = rendered.filter((c: any) => c.occluded);
      if (below.length) {
        L.push('   below the fold (needs sidebar scroll): ' + below.map((c: any) => c.label || c.text || c.el).join(', '));
      }
      if (occluded.length) {
        L.push('   occluded (centre pixel owned by another element): '
          + occluded.map((c: any) => `${c.label || c.text || c.el} -> ${c.hitTestOwner}`).join('; '));
      }
    }

    // 3. contrast
    L.push('');
    L.push('AXE (scoped to #ck-sidebar-nav, wcag2a/2aa/21a/21aa)');
    L.push(pad('state', 22) + pad('violations', 12) + pad('incomplete', 12) + pad('contrast nodes', 16) + 'detail');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      if (!s.axe) continue;
      const detail = s.axe.violations.map((v: any) => `${v.id} x${v.nodes.length}`).join(', ') || 'none';
      L.push(pad(stateId, 22) + pad(s.axe.ruleCounts.violations, 12) + pad(s.axe.ruleCounts.incomplete, 12)
        + pad(s.axe.ruleCounts.contrastNodesEvaluated, 16) + detail);
      for (const v of s.axe.violations) {
        for (const n of v.nodes.slice(0, 10)) {
          L.push('   VIOLATION ' + pad(v.id, 18) + pad(n.contrastRatio ?? '-', 8)
            + pad('need ' + (n.expectedContrastRatio ?? '-'), 12) + String(n.target).slice(0, 56));
        }
      }
      for (const v of s.axe.incomplete) {
        for (const n of v.nodes.slice(0, 6)) {
          L.push('   INCOMPLETE ' + pad(v.id, 18) + pad(String(n.target).slice(0, 44), 46) + n.message.slice(0, 70));
        }
      }
    }
    L.push('');
    L.push('MEASURED CONTRAST RATIOS (axe color-contrast, expanded desktop state)');
    L.push(pad('ratio', 8) + pad('need', 8) + pad('size/weight', 22) + pad('fg on bg', 22) + 'node');
    L.push(rule());
    for (const n of (r.states.B_expanded?.axe?.contrastPasses ?? [])) {
      L.push(pad(n.contrastRatio ?? '-', 8) + pad(n.expectedContrastRatio ?? '-', 8)
        + pad(`${n.fontSize ?? '?'} ${n.fontWeight ?? ''}`, 22)
        + pad(`${n.fgColor ?? '?'} / ${n.bgColor ?? '?'}`, 22) + String(n.target).slice(0, 44));
    }
    const badge = r.states.B_expanded?.badgeProbe;
    if (badge) {
      L.push('');
      L.push('.ck-nav-badge PROBE: ' + (badge.rendered
        ? `rendered "${badge.text}" ${badge.fontSizePx}px ${badge.rect.w}x${badge.rect.h} ${badge.color} on ${badge.background}`
        : `NOT rendered - ${badge.why}`));
    }

    // 4. accessible names
    L.push('');
    L.push('ACCESSIBLE NAMES');
    L.push(pad('state', 22) + pad('controls', 10) + pad('unnamed', 9) + 'source / unnamed elements');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const m = s.reachable ? s : s.drawerOpen;
      if (!m?.accessibleNames) continue;
      const ctrls = m.accessibleNames.controls;
      const unnamed = ctrls.filter((c: any) => !c.accessibleName && c.renderedDisplay !== 'none');
      L.push(
        pad(stateId, 22) + pad(ctrls.length, 10) + pad(unnamed.length, 9)
        + m.accessibleNames.source + (unnamed.length ? ' :: ' + unnamed.map((c: any) => c.el).join(', ') : ''),
      );
    }

    // 5. rail centring
    const rail = r.states.A_collapsed_rail;
    if (rail?.icons) {
      L.push('');
      L.push(`RAIL CENTRING (border-box centre-x = ${rail.railCentreX}, content-box centre-x = ${rail.railContentCentreX}, rail width = ${rail.sidebar.rect.w})`);
      L.push(pad('item', 30) + pad('glyph', 8) + pad('size', 14) + pad('centreX', 10) + pad('off(border)', 13) + 'off(content)');
      L.push(rule());
      for (const i of rail.icons) {
        const flag = i.offsetX !== null && Math.abs(i.offsetX) > 2 ? '  <-- >2px' : '';
        L.push(pad(i.item, 30) + pad(i.glyph, 8)
          + pad(i.glyphSize ? `${i.glyphSize.w}x${i.glyphSize.h}` : '-', 14)
          + pad(i.centreX ?? '-', 10) + pad(i.offsetX ?? '-', 13)
          + String(i.offsetXContentBox ?? '-') + flag);
      }
    }

    // 6. overflow
    L.push('');
    L.push('OVERFLOW / SCROLL');
    L.push(pad('state', 22) + pad('sb w', 8) + pad('groups', 8) + pad('items', 7) + pad('shown', 7)
      + pad('sb scrollH/clientH', 22) + 'nav scrollH/clientH');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const m = s.reachable ? s : s.drawerOpen;
      if (!m?.overflow) continue;
      const o = m.overflow;
      L.push(
        pad(stateId, 22) + pad(o.sidebar.width, 8) + pad(o.groupsRendered, 8)
        + pad(o.itemsRendered, 7) + pad(o.itemsVisibleWithoutScroll, 7)
        + pad(`${o.sidebar.scrollHeight}/${o.sidebar.clientHeight}${o.sidebar.scrolls ? ' SCROLLS' : ''}`, 22)
        + (o.nav ? `${o.nav.scrollHeight}/${o.nav.clientHeight}${o.nav.scrolls ? ' SCROLLS' : ''}` : '-'),
      );
    }

    // 7. reflow
    L.push('');
    L.push('REFLOW @ 320px (WCAG 1.4.10)');
    L.push(rule());
    const d = r.states.D_reflow_320;
    if (d) {
      for (const [k, m] of Object.entries<any>({ 'drawer closed': d.drawerClosed, 'drawer open': d.drawerOpen })) {
        L.push(pad(k, 22) + `scrollWidth=${m.reflow.scrollWidth} clientWidth=${m.reflow.clientWidth} bleed=${m.reflow.bleedPx}`
          + (m.reflow.offenders.length ? ` :: ${m.reflow.offenders.join(' | ')}` : ''));
      }
    }

    // 8. focus
    L.push('');
    L.push('FOCUS ORDER');
    L.push(rule());
    for (const [stateId, s] of Object.entries<any>(r.states)) {
      const f = s.focusOrder;
      if (!f) continue;
      L.push(`${stateId}: tab run starts at ${f.startingPoint}; ${f.stopsInsideSidebar} stops inside sidebar; first sidebar stop at Tab #${f.firstStopIndex + 1}`);
      L.push(`   :has(:focus-visible) fired = ${f.focusVisibleExpansionFired}; sidebar width at stops inside = [${f.widthsWhileFocused.join(', ')}]; after leaving = ${f.widthAfterLeaving}`);
      L.push(`   reached .ck-sb-pin = ${f.reachedPinButton}; reached .ck-sb-close = ${f.reachedCloseButton}; focus trap = ${f.focusTrapSuspected}`);
      if (f.focusableWhileHidden.length) {
        L.push(`   focusable while visually hidden (${f.focusableWhileHidden.length}): `
          + f.focusableWhileHidden.map((x: any) => `${x.el}[${x.name}]`).join(', '));
      }
      L.push('   sequence: ' + f.steps.map((x: any) => (x.inSidebar ? '' : '~') + (x.name || x.el)).join(' > '));
    }
  }

  L.push('');
  L.push('JSON: ' + outFile);
  L.push('='.repeat(104));
  console.log(L.join('\n'));
}
