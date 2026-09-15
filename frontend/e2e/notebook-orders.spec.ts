import { expect, test, type Page } from '@playwright/test';
import type { FormDefinition, FormInput, FormOrderDetail, ProductOption } from '../src/features/catalog/types';

const option = (id: number, groupId: number, code: string, label: string, extra = {}): ProductOption => ({
  id, groupId, code, label, specText: '', widthMm: null, heightMm: null,
  specStatus: 'CONFIRMED', sortOrder: id, active: true, ...extra,
});
const definition: FormDefinition = {
  enabled: true,
  category: { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: 'School notebooks', orderType: 'Recurring', formEnabled: true, sortOrder: 1, active: true },
  groups: [
    { id: 1, categoryCode: 'NOTEBOOKS', code: 'CUSTOMIZATION', label: 'Customization', level: 1, selectionType: 'SINGLE', required: true, scope: 'ORDER', active: true, options: [option(1, 1, 'CUSTOMIZED', 'Customized'), option(2, 1, 'NON_CUSTOMIZED', 'Non-customized')] },
    { id: 2, categoryCode: 'NOTEBOOKS', code: 'SIZE', label: 'Size', level: 2, selectionType: 'SINGLE', required: true, scope: 'LINE', active: true, options: [
      option(3, 2, 'LONG', 'Long', { specText: '17 cm x 27 cm', widthMm: 170, heightMm: 270 }),
      option(4, 2, 'JUMBO_LONG', 'Jumbo Long', { specText: '18 cm x 24 cm', widthMm: 180, heightMm: 240 }),
      option(5, 2, 'KING', 'King', { specStatus: 'PENDING_SPEC' }),
      option(6, 2, 'FA_A4', 'FA / A4 notebook', { specText: '21 cm x 29.7 cm', widthMm: 210, heightMm: 297 }),
      option(7, 2, 'DRAWING_BOOK', 'Drawing book', { specStatus: 'PENDING_SPEC' }),
    ] },
    { id: 3, categoryCode: 'NOTEBOOKS', code: 'RULING', label: 'Ruling', level: 3, selectionType: 'SINGLE', required: true, scope: 'LINE', active: true, options: [option(8, 3, 'SINGLE_RULE', 'Single rule'), option(9, 3, 'SPECIAL_MATH_RULE', 'Special math rule'), option(10, 3, 'ONE_SIDE_BROAD_RULE', 'One side broad rule')] },
  ],
  dependencies: [],
  rules: [
    { id: 1, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_QUANTITY_TOTAL', targetField: 'BOOK_COUNT', matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { value: 1000, comparison: 'EQ', scope: 'ORDER', stage: 'ON_PLACE' }, priority: 10, active: true, message: '' },
    { id: 2, categoryCode: 'NOTEBOOKS', ruleType: 'ROUND_TO_MULTIPLE', targetField: 'PAGE_COUNT', matchOptions: {}, params: { multiple: 7, mode: 'NEAREST', minimum: 7 }, priority: 20, active: true, message: '' },
    { id: 3, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_ASSET', targetField: null, matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { assetKind: 'DESIGN', stage: 'ON_PLACE' }, priority: 30, active: true, message: '' },
    { id: 4, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_ASSET', targetField: null, matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { assetKind: 'PRE_DELIVERY_PHOTO', stage: 'BEFORE_DELIVERY' }, priority: 40, active: true, message: '' },
  ],
};
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l3sAAAAASUVORK5CYII=', 'base64');
const permissions = ['school:read', 'order:read', 'order:create', 'order:update', 'order:fulfill'];
const commandCenter = {
  fees: { defaulterCount: 0, totalOverdueAmountPaise: 0, oldestDueDays: 0, totalDefaulters: 0 },
  photography: { eventId: null, collectedAmount: 0, pendingAmount: 0, targetAmount: 0, pendingReviewCount: 0, longAbsenceCount: 0 },
  lifecycle: { pendingReviewCount: 0, longAbsenceCount: 0, sectionsBelowThresholdCount: 0, thresholdPercent: 75 },
  attendance: { sectionsBelowThresholdCount: 0, thresholdPercent: 75, eventId: null, title: null },
  vendorDues: { catalogOrderCount: 0, catalogOrderTotalPaise: 0, firefightingCount: 0, firefightingTotalPaise: 0, totalDuesPaise: 0 },
  reorderSignals: { alertCount: 0 },
};
function savedOrder(form: FormDefinition, input: FormInput): FormOrderDetail {
  const selections = (codes: Record<string, string>) => Object.fromEntries(Object.entries(codes).map(([key, code]) => {
    const selected = form.groups.find((group) => group.code === key)?.options.find((item) => item.code === code);
    return [key, { code, label: selected?.label || code, specText: selected?.specText || '' }];
  }));
  return {
    order: { id: 'CK-1001', schoolId: 7, category: 'NOTEBOOKS', status: 'DRAFT', subtotal: 0, gst: 0, totalAmount: 0, formVersion: 2, pricingStatus: 'PENDING_PRICING', schoolName: 'Green Valley School' },
    formDefinition: structuredClone(form), orderSelections: selections(input.orderSelections),
    lines: input.lines.map((line, index) => ({ id: 101 + index, lineNo: index + 1, optionSelections: selections(line.selections), requestedBookCount: line.bookCount, bookCount: line.bookCount, requestedPageCount: line.pageCount, pageCount: line.pageCount === 198 ? 196 : line.pageCount, unitPricePaise: null, lineTotalPaise: null })),
    assets: [], formVersion: 2, pricingStatus: 'PENDING_PRICING', version: 0, quantityRuleResults: [],
  };
}
async function setup(page: Page, role: 'ADMIN' | 'SUPERADMIN' = 'ADMIN', placed = false) {
  const state = { form: structuredClone(definition), detail: null as FormOrderDetail | null, creates: 0, placements: 0, quotes: 0, patches: [] as Record<string, unknown>[] };
  if (placed) {
    state.detail = savedOrder(state.form, { orderSelections: { CUSTOMIZATION: 'NON_CUSTOMIZED' }, lines: [{ selections: { SIZE: 'LONG', RULING: 'SINGLE_RULE' }, bookCount: 100, pageCount: 198 }] });
    state.detail.order.status = 'PROCESSING';
  }
  const user = { accessToken: 'notebook-test-token', userId: 42, fullName: 'Asha Admin', email: 'asha@example.test', role, branchId: 7, branchName: 'Green Valley School', permissions: role === 'SUPERADMIN' ? [...permissions, 'platform:admin', 'catalog:manage', 'catalog:quote', 'order:approve'] : permissions };
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    let body: unknown = {};
    if (path.includes('/auth/')) body = user;
    else if (path === '/workspace') body = { school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' }, dashboard: {}, orders: [], staff: [] };
    else if (path.endsWith('/modules/active')) body = ['ORDERS', 'SUPPLY_OS', 'ERP'].map((moduleCode) => ({ moduleCode }));
    else if (path.includes('command-center') || path.includes('command-centre/brief')) body = commandCenter;
    else if (path === '/supply/product-catalog/categories') body = [state.form.category];
    else if (path === '/supply/product-catalog/forms/NOTEBOOKS') body = state.form;
    else if (/^\/supply\/product-catalog\/rules\/\d+$/.test(path) && method === 'PATCH') {
      const patch = route.request().postDataJSON(); state.patches.push(patch);
      const rule = state.form.rules.find((item) => item.id === Number(path.split('/').pop())); Object.assign(rule!, patch); body = rule;
    } else if (path === '/supply/orders' && method === 'POST') {
      const request = route.request().postDataJSON(); state.creates++;
      expect(request).not.toHaveProperty('subtotal'); expect(request).not.toHaveProperty('totalAmount');
      state.detail = savedOrder(state.form, request.orderData); body = state.detail.order;
    } else if (path === '/supply/orders/CK-1001' && method === 'PATCH') {
      const previous = state.detail!; const request = route.request().postDataJSON();
      expect(request.version).toBe(previous.version);
      state.detail = { ...savedOrder(previous.formDefinition, request.orderData), assets: previous.assets, version: previous.version + 1 }; body = state.detail;
    } else if (path === '/supply/orders/CK-1001/assets' && method === 'POST') {
      const asset = { id: 1, assetKind: 'DESIGN' as const, contentType: 'image/png', sizeBytes: png.length, originalFilename: 'school-cover.png', contentUrl: '/api/v1/supply/orders/CK-1001/assets/1/content', uploadedAt: '2026-09-15T10:00:00Z' };
      state.detail!.assets = [asset]; state.detail!.version++; body = asset;
    } else if (path.endsWith('/assets/1/content')) { await route.fulfill({ status: 200, contentType: 'image/png', body: png }); return;
    } else if (path === '/supply/orders/CK-1001/place') {
      state.placements++; state.detail!.order.status = state.detail!.assets.length ? 'DESIGN_APPROVAL' : 'PROCESSING'; state.detail!.version++; body = state.detail!.order;
    } else if (path === '/supply/orders/CK-1001/quote') {
      const quote = route.request().postDataJSON(); expect(quote.version).toBe(state.detail!.version); state.quotes++;
      state.detail!.lines.forEach((line) => { line.unitPricePaise = quote.lines.find((price: { id: number }) => price.id === line.id).unitPricePaise; line.lineTotalPaise = line.bookCount * line.unitPricePaise!; });
      state.detail!.order.subtotal = state.detail!.lines.reduce((total, line) => total + line.lineTotalPaise!, 0);
      state.detail!.order.gst = quote.gstPaise; state.detail!.order.totalAmount = state.detail!.order.subtotal + quote.gstPaise;
      state.detail!.pricingStatus = 'QUOTED'; state.detail!.order.pricingStatus = 'QUOTED'; state.detail!.version++; body = state.detail;
    } else if (path === '/supply/orders/CK-1001/superadmin-approve') {
      expect(state.detail!.pricingStatus).toBe('QUOTED'); state.detail!.order.status = 'APPROVED'; state.detail!.version++; body = state.detail!.order;
    } else if (path === '/supply/orders/CK-1001/form') body = state.detail;
    else if (path === '/sa/orders' || path === '/supply/orders' || path === '/orders') body = state.detail ? [state.detail.order] : [];
    else if (/(schools|staff|invoices|students|sections|classes|years|approvals)$/.test(path)) body = [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  await page.goto('/dashboard');
  return state;
}
async function openSchoolForm(page: Page) {
  await page.locator('button.ck-nav-item[aria-label="Supply Details"]').first().click();
  await page.mouse.move(1000, 200);
  await page.getByRole('button', { name: /^Notebooks School notebooks/ }).click();
  await expect(page.getByRole('heading', { name: 'Notebooks order' })).toBeVisible();
}

test('customized notebooks combine every size and ruling into one exact total', async ({ page }) => {
  const state = await setup(page); await openSchoolForm(page);
  await expect(page.getByLabel('Size for line 1').locator('option[value="KING"]')).toHaveAttribute('disabled', '');
  await expect(page.getByLabel('Size for line 1').locator('option[value="DRAWING_BOOK"]')).toHaveAttribute('disabled', '');
  await expect(page.getByLabel('Size for line 1').locator('option[value="FA_A4"]')).toHaveCount(1);
  await page.getByLabel('Ruling for line 1').selectOption('SPECIAL_MATH_RULE');
  await page.getByLabel('Books for line 1', { exact: true }).fill('400');
  await page.getByLabel('Printed pages for line 1').fill('198');
  await page.getByLabel('Printed pages for line 1').blur();
  await expect(page.getByText('Rounded from 198 to 196')).toBeVisible();
  await page.getByRole('button', { name: 'Add line', exact: true }).click();
  await page.getByLabel('Size for line 2').selectOption('JUMBO_LONG');
  await page.getByLabel('Ruling for line 2').selectOption('ONE_SIDE_BROAD_RULE');
  await page.getByLabel('Books for line 2', { exact: true }).fill('599');
  await expect(page.getByText('1 more books needed')).toBeVisible();
  await page.getByRole('button', { name: 'Place order', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('combined book count');
  expect(state.creates).toBe(0);
  await page.getByLabel('Books for line 2', { exact: true }).fill('601');
  await expect(page.getByText('1 books over the required total')).toBeVisible();
  await page.getByLabel('Books for line 2', { exact: true }).fill('600');
  await expect(page.getByText('Required total met')).toBeVisible();
  await page.getByRole('region', { name: 'Design artwork' }).locator('input[type="file"]').setInputFiles({ name: 'school-cover.png', mimeType: 'image/png', buffer: png });
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Draft CK-1001 saved' })).toBeVisible();
  expect(state.detail!.lines.map((line) => line.bookCount)).toEqual([400, 600]);
  expect(state.detail!.lines[0].pageCount).toBe(196);
  await page.getByRole('button', { name: 'Place order', exact: true }).click();
  await expect.poll(() => state.placements).toBe(1);
  expect(state.creates).toBe(1); expect(state.detail!.order.status).toBe('DESIGN_APPROVAL');
});

test('non-customized books submit without artwork, a 1000 minimum, or school prices', async ({ page }) => {
  const state = await setup(page); await openSchoolForm(page);
  await page.getByLabel('Customization', { exact: true }).selectOption('NON_CUSTOMIZED');
  await page.getByLabel('Books for line 1', { exact: true }).fill('25');
  await expect(page.getByText('All sizes combined')).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Design artwork' })).toHaveCount(0);
  await expect(page.getByLabel(/Unit price/)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save quote' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Place order', exact: true }).click();
  await expect.poll(() => state.placements).toBe(1);
  expect(state.detail!.order.status).toBe('PROCESSING'); expect(state.detail!.pricingStatus).toBe('PENDING_PRICING');
});

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
test(`superadmin quotes persisted quantities before final approval at ${viewport.width}px`, async ({ page }) => {
  await page.setViewportSize(viewport);
  const state = await setup(page, 'SUPERADMIN', true);
  if (viewport.width <= 768) await page.getByRole('button', { name: 'Open navigation menu', exact: true }).click();
  await page.locator('button.ck-nav-item[aria-label="All orders"]').first().click();
  await page.getByRole('row').filter({ hasText: 'CK-1001' }).getByRole('button', { name: 'View', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Approve order', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Approve design', exact: true })).toHaveCount(0);
  const priceInput = page.getByLabel('Unit price for line 1');
  await priceInput.scrollIntoViewIfNeeded();
  await expect(priceInput).toBeInViewport({ ratio: 1 });
  await expect(page.getByText('GST amount (Rs.)', { exact: true })).toBeVisible();
  await page.getByLabel('Unit price for line 1').fill('45.50');
  await page.getByLabel('GST amount in rupees').fill('546');
  await page.getByRole('button', { name: 'Save quote', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Quote saved' })).toBeVisible();
  expect(state.detail!.order.subtotal).toBe(455000); expect(state.detail!.order.totalAmount).toBe(509600);
  await page.getByRole('button', { name: 'Approve order', exact: true }).click();
  await expect.poll(() => state.detail!.order.status).toBe('APPROVED');
});
}

test('superadmin changes the combined quantity rule through guided controls', async ({ page }) => {
  const state = await setup(page, 'SUPERADMIN');
  await page.locator('button.ck-nav-item[aria-label="Catalog mgmt"]').first().click();
  await page.getByRole('tab', { name: 'Conditions', exact: true }).click();
  await page.getByRole('button', { name: 'Edit Require exactly 1000 books across all sizes', exact: true }).click();
  await page.getByLabel('Required books across the whole order').fill('1500');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Catalog changes saved' })).toBeVisible();
  expect(state.patches[0].params).toMatchObject({ value: 1500, scope: 'ORDER', comparison: 'EQ' });
  await page.getByRole('tab', { name: 'Preview', exact: true }).click();
  await page.getByLabel('Books for line 1', { exact: true }).fill('1500');
  await expect(page.getByText('1,500 / 1,500 books')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Place order', exact: true })).toHaveCount(0);
});
