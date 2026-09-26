import { expect, test, type Page } from '@playwright/test';
import { signInAs } from './support/session';

const LONG = 'Shri Guru Nanak Dev Memorial Senior Secondary Public School (Affiliated)';
const orders = Array.from({ length: 12 }, (_, i) => ({
  id: `CK-${1000 + i}`,
  schoolName: i % 3 === 0 ? LONG : 'DPS',
  category: i % 2 ? 'REPORT_CARDS' : 'NOTEBOOKS',
  totalAmount: [0, 50, 999900, 1400000000, 62400, 100][i % 6],
  status: ['QUOTED', 'PENDING_APPROVAL', 'AWAITING_APPROVAL', 'APPROVED'][i % 4],
  createdAt: '2026-09-21T10:00:00Z', placedAt: null,
  pricingStatus: i % 4 === 1 ? 'PENDING_PRICING' : 'QUOTED',
}));
const invoices = orders.slice(0, 8).map((o, i) => ({
  id: `INV-${2000 + i}`, school: o.schoolName, orderRef: o.id, total: o.totalAmount,
  status: i % 2 ? 'Paid' : 'Awaiting payment', issuedAt: '2026-09-21',
  description: i % 3 === 0 ? 'Ruled notebooks 172pp, custom cover, spine name printed, delivered to three campuses' : '',
}));
const schools = Array.from({ length: 6 }, (_, i) => ({
  id: i + 1, name: i % 2 ? LONG : 'DPS', shortCode: i % 2 ? 'SGNDMSSPS' : 'DPS',
  city: i % 2 ? 'Thiruvananthapuram' : 'Delhi', active: i % 3 !== 0,
  configuredClassCount: 12, configuredSectionCount: 4, academicYearStartMonth: 4,
  financialYearStartMonth: 4, timeZone: 'Asia/Kolkata', adminEmail: 'admin@example.test',
  ordersYTD: [0, 7, 1284][i % 3], gmvYTD: [0, 4800000, 129400000][i % 3],
}));

async function portal(page: Page) {
  await signInAs(page, { role: 'SUPERADMIN', fullName: 'Super Admin',
    permissions: ['platform:admin', 'order:create', 'order:read', 'order:update', 'catalog:manage', 'school:read', 'order:approve', 'catalog:quote'],
    modules: ['ORDERS', 'SUPPLY_OS', 'ERP'] });
  const json = (b: unknown) => (r: any) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
  await page.route('**/api/v1/sa/orders*', json(orders));
  await page.route('**/api/v1/sa/invoices/stats*', json({ sentThisMonth: 8, paid: 4, pending: 4, totalInvoiced: 1400062450 }));
  await page.route('**/api/v1/sa/invoices*', json(invoices));
  await page.route('**/api/v1/sa/schools*', json(schools));
  await page.route('**/api/v1/supply/orders/pending-approval*', json(orders.slice(0, 5)));
  await page.goto('/dashboard');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addStyleTag({ content: '*,*::before,*::after{transition:none!important;animation:none!important}' });
}

/**
 * Two-row fixtures hide what real content does to a table. With long school names, twelve rows
 * and amounts from zero to eight figures, identifiers broke across lines ("CK-" / "1000"),
 * dates split ("21 September" / "2026") and the text inside status pills and buttons wrapped,
 * so every row was a different height. A pill or a button that wraps reads as broken.
 */
const PANELS: [string, string][] = [
  ['All orders', 'All orders'],
  ['Invoices', 'Invoices'],
  ['Order approvals', 'Order approvals'],
];

for (const [label] of PANELS) {
  test(`atomic values do not wrap: ${label}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await portal(page);
    await page.locator('button.ck-nav-item').filter({ hasText: label }).first().click();
    await page.waitForSelector('.ck-table tbody tr', { timeout: 15000 });
    const wrapped = await page.evaluate(() => {
      const offenders: string[] = [];
      // A <td> is as tall as its row, so a long name in a neighbouring cell makes every cell
      // look wrapped. Measure the text itself: a Range over the contents reports one rect per
      // line box, so more than one distinct top means the value broke across lines.
      for (const el of Array.from(document.querySelectorAll<HTMLElement>(
        '.ck-table .ck-pill, .ck-table .ck-status, .ck-table .ck-btn, .ck-table .ck-num, .ck-table .tb'))) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const tops = new Set(Array.from(range.getClientRects())
          .filter((r) => r.width > 0 && r.height > 0)
          .map((r) => Math.round(r.top)));
        if (tops.size > 1) offenders.push(`${el.className || el.tagName}: "${(el.textContent || '').trim().slice(0, 30)}"`);
      }
      return Array.from(new Set(offenders)).slice(0, 8);
    });
    expect(wrapped, `${label}: ${wrapped.join(' | ')}`).toEqual([]);
  });
}
