import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { trophyDefinition } from '../src/features/catalog/catalogTestFixtures';
import type { FormInput, FormOrderDetail } from '../src/features/catalog/types';

const trophyProducts: { model: string; type: string; sizes: Record<string, number> }[] = JSON.parse(
  readFileSync(new URL('../src/features/catalog/trophies/catalogue.json', import.meta.url), 'utf8'));
const trophyVariantCode = (model: string, size: string) => `T_${model.toUpperCase().replace(/-/g, '_')}_${size}`;

async function setup(page: Page) {
  const definition = structuredClone(trophyDefinition);
  definition.groups[0].options = trophyProducts.flatMap((product) => Object.keys(product.sizes).map((size) => ({
    id: 0, groupId: 30, code: trophyVariantCode(product.model, size), label: `${product.model} / Size ${size}`,
    specText: product.type, widthMm: null, heightMm: null, specStatus: 'CONFIRMED' as const, active: true, sortOrder: 0,
  }))).map((option, index) => ({ ...option, id: index + 1, sortOrder: index }));
  const state = { detail: null as FormOrderDetail | null, creates: 0, places: 0, payload: {} as Record<string, unknown> };
  const user = { accessToken: 'trophy-test-token', userId: 42, fullName: 'Asha Admin', email: 'asha@example.test', role: 'ADMIN', branchId: 7, branchName: 'Green Valley School', permissions: ['school:read', 'student:read', 'order:read', 'order:create', 'order:update', 'catalog:read', 'notification:read', 'report:read'] };
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    let body: unknown = [];
    if (path.includes('/auth/')) body = user;
    else if (path === '/reporting/workspace') body = { school: { name: 'Green Valley School', timeZone: 'Asia/Kolkata' }, dashboard: {}, orders: [], staff: [] };
    else if (path.endsWith('/modules/active')) body = ['ORDERS', 'SUPPLY_OS', 'ERP'].map((moduleCode) => ({ moduleCode }));
    else if (path === '/supply/product-catalog/categories') body = [definition.category];
    else if (path === '/supply/product-catalog/forms/TROPHIES') body = definition;
    else if (path === '/catalog/orders' && method === 'POST') {
      state.creates++; state.payload = route.request().postDataJSON();
      const input = state.payload.orderData as FormInput;
      state.detail = { order: { id: 'CK-2001', category: 'TROPHIES', schoolId: 7, schoolName: 'Green Valley School', status: 'DRAFT', subtotal: 0, gst: 0, totalAmount: 0, formVersion: 2 },
        formDefinition: definition, orderSelections: {}, assets: [], formVersion: 2, pricingStatus: 'PENDING_PRICING', version: 0, quantityRuleResults: [],
        lines: input.lines.map((line, index) => ({ id: index + 1, lineNo: index + 1, optionSelections: { VARIANT: { code: line.selections.VARIANT, label: definition.groups[0].options.find((option) => option.code === line.selections.VARIANT)!.label } }, requestedBookCount: line.bookCount, bookCount: line.bookCount, requestedPageCount: 1, pageCount: 1, unitPricePaise: null, lineTotalPaise: null })) };
      body = state.detail.order;
    } else if (path === '/supply/orders/CK-2001' && method === 'PATCH') { state.detail!.version++; body = state.detail; }
    else if (path === '/supply/orders/CK-2001/form') body = state.detail;
    else if (path === '/catalog/orders/CK-2001/place') { state.places++; state.detail!.order.status = 'PROCESSING'; body = state.detail!.order; }
    else if (path === '/catalog/orders') body = state.detail ? [state.detail.order] : [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  await page.goto('/dashboard');
  return state;
}

for (const width of [1280, 390]) {
  test(`trophy catalogue saves and places a size-specific order at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await setup(page);
    if (width < 768) await page.getByRole('button', { name: 'Open navigation menu', exact: true }).click();
    await page.locator('button.ck-nav-item[aria-label="Supply Details"]').first().click();
    await page.mouse.move(width - 10, 200);
    await page.getByRole('button', { name: /Trophies by model and size/ }).click();
    await expect(page.getByRole('heading', { name: 'Trophies order' })).toBeVisible();
    await expect(page.getByRole('article')).toHaveCount(24);
    await page.getByLabel('Search trophy model').fill('9010');
    const productImage = page.getByAltText('Trophy model 9010');
    await expect(productImage).toBeVisible();
    await expect.poll(() => productImage.evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    await page.getByLabel('Search trophy model').fill('wm001');
    await page.getByLabel('Size for WM001').selectOption('B');
    await page.getByLabel('Quantity for WM001').fill('3');
    await page.getByRole('button', { name: 'Add WM001 size B to order' }).click();
    await expect(page.getByLabel('Ordered count for WM001 / Size B')).toHaveValue('3');
    await expect(page.getByText('₹8,403.00')).toBeVisible();
    const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
    expect(horizontalOverflow).toBe(false);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `test-results/trophy-catalogue-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: 'Save draft', exact: true }).click();
    await expect(page.getByText('Draft CK-2001 saved.')).toBeVisible();
    expect(state.payload.category).toBe('TROPHIES');
    expect(state.payload).not.toHaveProperty('totalAmount');
    expect(state.payload.orderData).toEqual({ orderSelections: {}, lines: [{ selections: { VARIANT: 'T_WM001_B' }, bookCount: 3, pageCount: 1 }] });
    await page.getByRole('button', { name: 'Place order', exact: true }).click();
    await expect.poll(() => state.places).toBe(1);
    expect(state.creates).toBe(1);
    expect(state.detail!.order.status).toBe('PROCESSING');
  });
}
