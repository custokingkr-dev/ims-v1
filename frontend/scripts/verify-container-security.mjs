import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';

const origin = process.env.FRONTEND_SECURITY_CONTAINER_URL || 'http://127.0.0.1:18789';
const evidence = { scope: 'Local built nginx container; no deployed environment tested', origin, headers: [], browser: {} };
const required = ['content-security-policy', 'x-content-type-options', 'x-frame-options', 'referrer-policy', 'permissions-policy', 'strict-transport-security'];
const html = await (await fetch(`${origin}/login`)).text();
const asset = html.match(/src="([^"]+\.js)"/)?.[1];
assert.ok(asset, 'Built script asset exists');
for (const path of ['/login', '/dashboard', '/index.html', '/frontend-health', asset, '/assets/missing-security-proof.js', '/api/v1/security-proof']) {
  const response = await fetch(`${origin}${path}`);
  const headers = Object.fromEntries(required.map(name => [name, response.headers.get(name)]));
  for (const name of required) assert.ok(headers[name], `${path}: ${name}`);
  assert.equal(headers['x-content-type-options'], 'nosniff');
  assert.equal(headers['x-frame-options'], 'DENY');
  assert.match(headers['content-security-policy'], /object-src 'none'/);
  if (path.includes('missing-security')) assert.equal(response.status, 404);
  if (path.startsWith('/api/')) assert.equal(response.status, 502);
  evidence.headers.push({ path, status: response.status, headers, cacheControl: response.headers.get('cache-control') });
}
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ acceptDownloads: true });
  await page.goto(`${origin}/login`);
  evidence.browser = await page.evaluate(async () => {
    const violations = [];
    document.addEventListener('securitypolicyviolation', event => violations.push(event.effectiveDirective));
    const script = document.createElement('script'); script.textContent = 'window.securityProofInlineExecuted=true'; document.body.append(script);
    const png = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l3sAAAAASUVORK5CYII='), c => c.charCodeAt(0));
    const image = new Image(); const loaded = new Promise(resolve => { image.onload = () => resolve(image.naturalWidth > 0); image.onerror = () => resolve(false); });
    image.src = URL.createObjectURL(new Blob([png], { type: 'image/png' })); document.body.append(image);
    const pdf = URL.createObjectURL(new Blob(['%PDF-1.4\n%%EOF'], { type: 'application/pdf' }));
    const object = document.createElement('object'); object.type = 'application/pdf'; object.data = pdf; document.body.append(object);
    const link = document.createElement('a'); link.textContent = 'Security proof download'; link.download = 'security-proof.pdf'; link.href = URL.createObjectURL(new Blob(['%PDF-1.4\n%%EOF'], { type: 'application/octet-stream' })); document.body.append(link);
    const blobRasterLoaded = await loaded; await new Promise(resolve => setTimeout(resolve, 150));
    return { inlineScriptExecuted: Boolean(window.securityProofInlineExecuted), blobRasterLoaded, violations };
  });
  assert.equal(evidence.browser.inlineScriptExecuted, false);
  assert.equal(evidence.browser.blobRasterLoaded, true);
  assert.ok(evidence.browser.violations.includes('script-src-elem'));
  assert.ok(evidence.browser.violations.includes('object-src'));
  const download = page.waitForEvent('download'); await page.getByText('Security proof download').click();
  assert.equal((await download).suggestedFilename(), 'security-proof.pdf'); evidence.browser.blobDownload = true;
  // Exercise the actual built OrderAssetField rather than a test-only rendering harness.
  const category = { code: 'NOTEBOOKS', label: 'Notebooks', description: 'School notebooks', emoji: '', orderType: 'Recurring', sortOrder: 1, active: true, formEnabled: true, paged: true };
  const order = { id: 'CK-SECURITY', schoolId: 7, category: 'NOTEBOOKS', status: 'PROCESSING', subtotal: 0, gst: 0, totalAmount: 0, formVersion: 2, pricingStatus: 'PENDING_PRICING', schoolName: 'Security fixture' };
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l3sAAAAASUVORK5CYII=', 'base64');
  const detail = { order, formDefinition: { enabled: true, category, groups: [], rules: [], dependencies: [] }, orderSelections: {}, lines: [], assets: [
    { id: 1, assetKind: 'DESIGN', originalFilename: 'artwork.pdf', contentType: 'application/pdf', sizeBytes: 14 },
    { id: 2, assetKind: 'PRE_DELIVERY_PHOTO', originalFilename: 'delivery.png', contentType: 'image/png', sizeBytes: png.length },
  ], version: 0, formVersion: 2, pricingStatus: 'PENDING_PRICING', quantityRuleResults: [] };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let body = {};
    if (path.includes('/auth/')) body = { accessToken: 'mock-security-token', sessionId: 'fixture-session', stepUpExpiresAt: null, userId: 42, fullName: 'Security fixture', email: 'fixture@example.test', role: 'SUPERADMIN', branchId: 7, permissions: ['platform:admin', 'school:read', 'order:read', 'order:update'] };
    else if (path.endsWith('/assets/1/content')) return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
    else if (path.endsWith('/assets/2/content')) return route.fulfill({ contentType: 'image/png', body: png });
    else if (path === '/supply/orders/CK-SECURITY/form') body = detail;
    else if (path === '/catalog/orders/page') body = { content: [order], page: 0, size: 20, totalElements: 1, totalPages: 1 };
    else if (path.endsWith('/modules/active')) body = ['ORDERS', 'SUPPLY_OS', 'ERP'].map(moduleCode => ({ moduleCode }));
    else if (path === '/reporting/workspace') body = { school: { name: 'Security fixture', meta: '2026-27', timeZone: 'Asia/Kolkata' }, dashboard: {}, orders: [], staff: [] };
    else if (path.includes('command-center') || path.includes('command-centre/brief')) body = { fees: {}, photography: {}, lifecycle: {}, attendance: {}, vendorDues: {}, reorderSignals: {} };
    else if (path.endsWith('/categories')) body = [category];
    else if (/(schools|staff|invoices|students|sections|classes|years|approvals)$/.test(path)) body = [];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
  await page.goto(`${origin}/dashboard`);
  await page.locator('button.ck-nav-item[aria-label="All orders"]').first().click();
  await page.getByRole('row').filter({ hasText: 'CK-SECURITY' }).getByRole('button', { name: 'View', exact: true }).click();
  const raster = page.getByRole('img', { name: 'Pre-delivery photo', exact: true });
  await raster.waitFor();
  assert.equal(await raster.evaluate(image => image.complete && image.naturalWidth > 0), true);
  assert.equal(await page.getByRole('region', { name: 'Design artwork' }).locator('img,iframe,object,embed').count(), 0);
  const realDownload = page.waitForEvent('download'); await page.getByRole('link', { name: 'Download artwork.pdf' }).click();
  assert.equal((await realDownload).suggestedFilename(), 'artwork.pdf');
  evidence.browser.actualOrderAssetField = { rasterBlobPreviewLoaded: true, pdfInlineDocumentCount: 0, pdfBlobDownload: true, api: 'mocked backend; actual production bundle and nginx CSP' };
} finally { await browser.close(); }
await writeFile(new URL('../../docs/security-remediation/browser-evidence.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
console.log('Built container security header and Chromium enforcement checks passed.');
