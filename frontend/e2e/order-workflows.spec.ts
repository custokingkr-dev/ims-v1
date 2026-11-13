import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';
import { NOW, makeSummary, makeView, normaliseTemplate } from '../src/features/orders/workflow/testFixtures';
import { signInAs } from './support/session';

/**
 * The order workflow screens in a real browser (design sections 4.2-4.5 and 4.9): the list, one notebook order's workflow
 * rendered from the template operations-service ships, and Workflow set-up. Each is checked for colour contrast and for
 * sideways page scrolling at the four widths the design names.
 */

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const NB = normaliseTemplate(JSON.parse(readFileSync(
  join(ROOT, 'services', 'operations-service', 'src', 'main', 'resources', 'workflow-templates', 'NOTEBOOK_ORDER_TRACKER.json'), 'utf8')));
const WIDTHS = [375, 768, 1024, 1280] as const;

const VIEW = makeView(NB, {
  active: 5,
  superadmin: true,
  view: {
    vendorSelections: { BOARD_SUPPLIERS: [1], BINDERS: [3] },
    groups: {
      school: { url: 'https://chat.whatsapp.com/SchoolGroup1234', source: 'SCHOOL' },
      internalApproval: { url: 'https://chat.whatsapp.com/Approval12345', source: 'GLOBAL' },
      vendors: [
        { id: 1, name: 'Shree Board Traders', categories: ['BOARD_SUPPLIERS'], contactPerson: 'Mohan', phone: '+91 90000 00001', city: 'Delhi', whatsappGroupUrl: 'https://chat.whatsapp.com/Vendor1Group12', sample: false, active: true },
        { id: 3, name: 'Perfect Bindery', categories: ['BINDERS'], contactPerson: null, phone: '+91 90000 00003', city: 'Delhi', whatsappGroupUrl: null, sample: false, active: true },
      ],
    },
    upcomingActions: [
      { kind: 'LAST_COMPLETED', title: 'Head Approval', stepOrder: 4, dueAt: null, dueState: 'NONE', done: true },
      { kind: 'STAGE_DEADLINE', title: 'Production Planning due', stepOrder: 5, dueAt: '2026-11-13T09:30:00Z', dueState: 'DUE_TODAY', done: false },
    ],
  },
});
const LIST_ITEM = {
  orderId: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042', definitionId: 'NOTEBOOK_ORDER_TRACKER', categoryCode: 'NOTEBOOKS', itemCode: 'NB', schoolId: 7,
  schoolName: 'Delhi Public School', customised: true, orderedCount: 15600, placedAt: '2026-11-11T06:30:00Z', timeZone: 'Asia/Kolkata', status: 'PRODUCTION_PLANNING',
  phase: 'PLANNING', currentStep: 5, currentTitle: 'Production Planning', assigneeUserId: 42, assigneeName: 'Asha Operator', dueAt: '2026-11-13T09:30:00Z',
  overdue: false, slaState: 'DUE_SOON', completed: 4, total: 12,
};
const OVERDUE_ITEM = { ...LIST_ITEM, orderId: 'CK-1016', orderNumber: null, currentStep: 2, currentTitle: 'Designing Activity', phase: 'INTAKE',
  status: 'DESIGNING_ACTIVITY', assigneeUserId: null, assigneeName: null, dueAt: '2026-11-10T09:30:00Z', overdue: true, slaState: 'OVERDUE', completed: 1 };
const CATEGORIES = [
  { code: 'BOARD_SUPPLIERS', name: 'Board Suppliers', sortOrder: 1, active: true, vendorCount: 1, updatedAt: null, updatedBy: null },
  { code: 'BINDERS', name: 'Binders', sortOrder: 3, active: true, vendorCount: 1, updatedAt: null, updatedBy: null },
];
const VENDORS = VIEW.groups.vendors.map((vendor) => ({ ...vendor, altPhone: null, email: null, notes: null, updatedAt: NOW, updatedBy: 1 }));

async function json(route: Route, body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

/** The workflow endpoints these screens read; anything else falls through to the session mock. */
async function serveWorkflows(page: Page) {
  const byPath: Record<string, unknown> = {
    '/api/v1/workflows/item-orders': { items: [LIST_ITEM, OVERDUE_ITEM], total: 2, counts: { open: 2, overdue: 1, dueToday: 1, unassigned: 1, mine: 1 }, now: NOW },
    '/api/v1/workflows/item-orders/templates': [NB],
    '/api/v1/workflows/item-orders/templates/NOTEBOOK_ORDER_TRACKER': NB,
    '/api/v1/workflows/item-orders/CK-1015': VIEW,
    '/api/v1/workflows/item-orders/CK-1015/calls': [],
    '/api/v1/workflows/item-orders/CK-1015/activity': { entries: [], nextBefore: null },
    '/api/v1/catalog/orders/CK-1015/tracker-summary': makeSummary(),
    '/api/v1/catalog/orders/CK-1015/assets': [],
    '/api/v1/supply/product-catalog/categories': [{ code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: 'School notebooks', orderType: 'Recurring',
      formEnabled: true, sortOrder: 1, active: true, orderCode: 'NB', itemNoun: 'notebook', workflowDefinition: 'NOTEBOOK_ORDER_TRACKER' }],
    '/api/v1/workflows/designers': [{ id: 3, name: 'Priya Singh', phone: '98100 00003', active: true }],
    '/api/v1/workflows/vendors': VENDORS,
    '/api/v1/workflows/vendor-categories': CATEGORIES,
    '/api/v1/workflows/settings/internal-approval-group': { inviteUrl: null, updatedAt: null, updatedBy: null },
    '/api/v1/rbac/operators': [],
    '/api/v1/users': [],
    '/api/v1/sa/schools': [{ id: 7, name: 'Delhi Public School', city: 'Gurugram' }],
  };
  // Registered after the session mock, so it is asked first.
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === 'GET' && path in byPath) return json(route, byPath[path]);
    return route.fallback();
  });
}

async function open(page: Page, search: string) {
  await signInAs(page, {
    role: 'SUPERADMIN',
    fullName: 'Super Admin',
    permissions: ['platform:admin', 'order:create', 'order:read', 'order:update', 'catalog:manage', 'school:read', 'notebook-tracker:read'],
    modules: ['ORDERS', 'SUPPLY_OS', 'ERP'],
  });
  await serveWorkflows(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/dashboard${search}`);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addStyleTag({ content: '*,*::before,*::after{transition:none !important;animation:none !important}' });
}

async function contrastViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
  return results.violations.flatMap((violation) => violation.nodes.map((node) => {
    const data = (node.any?.[0] as { data?: Record<string, unknown> } | undefined)?.data ?? {};
    return { html: node.html.slice(0, 90), ratio: data.contrastRatio, fg: data.fgColor, bg: data.bgColor };
  }));
}

/** Elements that reach past the viewport outside any horizontal scroller, and whether the page itself scrolls sideways. */
async function overflow(page: Page) {
  return page.evaluate(() => {
    const docWidth = document.documentElement.clientWidth;
    const offenders: string[] = [];
    const inScroller = (el: HTMLElement) => {
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const ox = getComputedStyle(p).overflowX;
        if (ox === 'auto' || ox === 'scroll') return true;
      }
      return false;
    };
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0 || inScroller(el)) continue;
      if (rect.right > docWidth + 1) {
        const cls = typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : '';
        offenders.push(`${el.tagName.toLowerCase()}${cls} (right=${Math.round(rect.right)} vs ${docWidth})`);
      }
    }
    return { scroll: document.documentElement.scrollWidth, client: docWidth, offenders: Array.from(new Set(offenders)).slice(0, 6) };
  });
}

async function expectReflows(page: Page, label: string) {
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 900 });
    await page.waitForTimeout(250);
    const measured = await overflow(page);
    expect(measured.offenders, `${label} @ ${width}: ${measured.offenders.join(' | ')}`).toEqual([]);
    expect(measured.scroll, `${label} @ ${width} scrolls sideways`).toBeLessThanOrEqual(measured.client + 1);
  }
}

test('the order list shows numbers, stages and due states, with readable contrast and no sideways scroll', async ({ page }) => {
  await open(page, '?panel=nb-work');
  const row = page.getByRole('row', { name: /NB-DPS_GUR-26-11-13-0042/ });
  await expect(row).toContainText('Ref CK-1015');
  await expect(row).toContainText('5 · Production Planning');
  await expect(page.getByRole('row', { name: /CK-1016/ })).toContainText('Unassigned');
  expect(await contrastViolations(page)).toEqual([]);
  await expectReflows(page, 'Order workflows list');
});

/** The order's stepper: every stage of its path, each a button. */
const stepperOf = (page: Page) => page.getByRole('navigation', { name: 'Workflow stages', exact: true });

test('an order opens on its current stage, rendered from the shipped template, readable and reflowing at every width', async ({ page }) => {
  test.setTimeout(60_000); // axe on four tabs and a reflow sweep at four widths, twice
  await open(page, '?panel=nb-work&order=CK-1015');
  await expect(page.getByRole('heading', { level: 3, name: 'Step 5: Production Planning' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: 'NB-DPS_GUR-26-11-13-0042' })).toBeVisible();
  await expect(page.locator('[aria-current="step"]')).toHaveCount(1);
  // Every stage of the template is on the stepper.
  for (const step of NB.steps) await expect(stepperOf(page)).toContainText(step.title);
  expect(await contrastViolations(page)).toEqual([]);

  await page.getByRole('tab', { name: 'School & vendor' }).click();
  await expect(page.getByRole('form', { name: 'Log a call or discussion' })).toBeVisible();
  expect(await contrastViolations(page)).toEqual([]);
  await expectReflows(page, 'Order workflow, School & vendor');

  await page.setViewportSize({ width: 1280, height: 900 });
  for (const tab of ['Checklist', 'Activity history']) {
    await page.getByRole('tab', { name: tab }).click();
    await expect(page.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
    expect(await contrastViolations(page), `${tab} tab`).toEqual([]);
  }
  await page.getByRole('tab', { name: 'Work area' }).click();
  await expectReflows(page, 'Order workflow, Work area');
});

test("every stage's cards, completed, current and locked, are readable as the template declares them", async ({ page }) => {
  test.setTimeout(90_000); // axe once per stage
  await open(page, '?panel=nb-work&order=CK-1015');
  await expect(page.getByRole('heading', { level: 3, name: 'Step 5: Production Planning' })).toBeVisible();
  for (const step of NB.steps) {
    await stepperOf(page).getByRole('button', { name: new RegExp(`^Step ${step.order}: `) }).click();
    await expect(page.getByRole('heading', { level: 3, name: `Step ${step.order}: ${step.title}` })).toBeVisible();
    expect(await contrastViolations(page), `Step ${step.order}: ${step.title}`).toEqual([]);
  }
});

test('the order lays out as section 4.3 asks with the navigation pinned, as it is by default', async ({ page }) => {
  await open(page, '?panel=nb-work&order=CK-1015');
  await expect(page.getByRole('heading', { level: 3, name: 'Step 5: Production Planning' })).toBeVisible();
  // Three columns at 1280, the sidebar below at 1024, and one column with a stage select at 768 and 375.
  const box = async (selector: string) => (await page.locator(selector).first().boundingBox())!;
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.waitForTimeout(250);
  const [list, main, side] = [await box('.ck-wf-stages'), await box('.ck-wf-main'), await box('.ck-wf-side')];
  expect(main.x, 'stage panel beside the stage list at 1280').toBeGreaterThan(list.x + list.width - 1);
  expect(side.x, 'sidebar beside the stage panel at 1280').toBeGreaterThan(main.x + main.width - 1);
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.waitForTimeout(250);
  await expect(page.getByRole('navigation', { name: 'Order workflow stages' })).toBeVisible();
  const [main1024, side1024] = [await box('.ck-wf-main'), await box('.ck-wf-side')];
  expect(side1024.y, 'sidebar below the stage panel at 1024').toBeGreaterThan(main1024.y + main1024.height - 1);
  for (const width of [768, 375]) {
    await page.setViewportSize({ width, height: 900 });
    await expect(page.getByRole('combobox', { name: 'Stage' })).toBeVisible();
  }
});

test('Workflow set-up lists vendors by category, readable and reflowing at every width', async ({ page }) => {
  test.setTimeout(60_000); // two reflow sweeps at four widths
  await open(page, '?panel=sa-nb-admin');
  await expect(page.getByRole('heading', { name: 'Workflow set-up' })).toBeVisible();
  await expect(page.getByText('Perfect Bindery').first()).toBeVisible();
  expect(await contrastViolations(page)).toEqual([]);
  await expectReflows(page, 'Workflow set-up, Vendors');
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole('tab', { name: 'Time slots' }).click();
  await expect(page.getByRole('table', { name: /Default time slots/ })).toBeVisible();
  expect(await contrastViolations(page)).toEqual([]);
  await expectReflows(page, 'Workflow set-up, Time slots');
});
