import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { chromium } from '../../frontend/node_modules/@playwright/test/index.mjs';

// Temporary read-only smoke: no credentials, real student identifiers, or cloud writes.
const origins = (process.env.SMOKE_ORIGINS || 'https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app').split(',');
const evidence = { startedAt: new Date().toISOString(), scope: 'Anonymous live HTTP/browser smoke', origins: [], nonRoot: { verified: false, reason: 'Public HTTP cannot establish runtime UID; use deployed image config plus existing container proof.' } };
const required = ['content-security-policy', 'x-content-type-options', 'x-frame-options', 'referrer-policy', 'permissions-policy', 'strict-transport-security'];
async function read(url) {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 12000);
  let response;
  try {
    response = await fetch(url, { signal: controller.signal, redirect: 'error', headers: { 'Cache-Control': 'no-cache' } });
    const reader = response.body.getReader(); let bytes = 0; const chunks = [];
    while (true) { const part = await reader.read(); if (part.done) break; bytes += part.value.length; assert.ok(bytes <= 2 * 1024 * 1024, 'Response exceeds smoke body limit'); chunks.push(Buffer.from(part.value)); }
    return { status: response.status, headers: response.headers, body: Buffer.concat(chunks).toString('utf8') };
  } finally { clearTimeout(timer); if (response?.body && !response.body.locked) await response.body.cancel().catch(() => {}); }
}
const browser = await chromium.launch({ timeout: 15000 });
try {
  for (const origin of origins) {
    const parsed = new URL(origin); assert.equal(parsed.protocol, 'https:'); assert.match(parsed.hostname, /^custoking-frontend-dev-[a-z0-9-]+\.a\.run\.app$|^custoking-frontend-dev-[0-9]+\.[a-z0-9-]+\.run\.app$/); assert.equal(parsed.pathname, '/');
    const item = { origin, responses: [], browser: {} }; evidence.origins.push(item);
    const html = await read(`${origin}/login`); const asset = html.body.match(/src="(\/assets\/[^"?]+\.js)"/)?.[1]; assert.ok(asset, 'Built JS asset required');
    for (const path of ['/login', '/frontend-health', asset, '/assets/missing-security-proof.js', '/api/v1/students/0/photo/content']) {
      const response = await read(`${origin}${path}`); const headers = Object.fromEntries(required.map(name => [name, response.headers.get(name)]));
      item.responses.push({ resource: path.includes('/students/') ? 'synthetic-nonexistent-photo' : path, status: response.status, headers });
      for (const name of required) assert.ok(headers[name], `Missing ${name}`);
      assert.equal(headers['x-content-type-options'], 'nosniff'); assert.equal(headers['x-frame-options'], 'DENY'); assert.match(headers['content-security-policy'], /object-src 'none'/); assert.match(headers['content-security-policy'], /script-src 'self'/);
      if (path === '/frontend-health') assert.equal(response.status, 200);
      else if (path.includes('missing-security')) assert.equal(response.status, 404);
      else if (path.includes('/students/')) { assert.ok([401, 403].includes(response.status), 'Anonymous synthetic photo must be denied before resource lookup'); assert.ok(!response.headers.get('content-type')?.startsWith('image/')); }
      else assert.equal(response.status, 200);
    }
    const context = await browser.newContext(); const page = await context.newPage(); page.setDefaultTimeout(12000);
    // Prevent page telemetry/API requests: HTTP authorization was tested separately above.
    await page.route('**/api/**', route => route.abort());
    await page.goto(`${origin}/login`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    item.browser = await page.evaluate(async () => {
      const violations = []; document.addEventListener('securitypolicyviolation', event => violations.push(event.effectiveDirective));
      const script = document.createElement('script'); script.textContent = 'window.smokeInlineExecuted=true'; document.body.append(script);
      const object = document.createElement('object'); object.data = URL.createObjectURL(new Blob(['%PDF-1.4\n%%EOF'], { type: 'application/pdf' })); document.body.append(object);
      const img = new Image(); const loaded = new Promise(resolve => { img.onload = () => resolve(img.naturalWidth > 0); img.onerror = () => resolve(false); setTimeout(() => resolve(false), 3000); });
      img.src = URL.createObjectURL(new Blob([Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l3sAAAAASUVORK5CYII='), c => c.charCodeAt(0))], { type: 'image/png' })); document.body.append(img);
      const rasterBlobLoaded = await loaded; await new Promise(resolve => setTimeout(resolve, 200)); return { inlineExecuted: Boolean(window.smokeInlineExecuted), rasterBlobLoaded, violations };
    });
    assert.equal(item.browser.inlineExecuted, false); assert.equal(item.browser.rasterBlobLoaded, true); assert.ok(item.browser.violations.includes('script-src-elem')); assert.ok(item.browser.violations.includes('object-src'));
    await context.close();
  }
  evidence.success = true;
} catch (error) { evidence.success = false; evidence.failure = String(error.message).slice(0, 300); process.exitCode = 1; }
finally { await browser.close(); evidence.finishedAt = new Date().toISOString(); await writeFile(process.env.SMOKE_EVIDENCE || 'tmp/postcutover-frontend-smoke.json', JSON.stringify(evidence, null, 2) + '\n'); }
console.log(JSON.stringify({ success: evidence.success, origins: evidence.origins.length, nonRootVerified: false }));
