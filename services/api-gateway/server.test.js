'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

for (const name of [
  'IDENTITY_SERVICE_TOKEN',
  'TENANT_SCHOOL_SERVICE_TOKEN',
  'STUDENT_SERVICE_TOKEN',
  'ATTENDANCE_SERVICE_TOKEN',
  'FEE_SERVICE_TOKEN',
  'CATALOG_SERVICE_TOKEN',
  'WORKFLOW_SERVICE_TOKEN',
  'FIREFIGHTING_SERVICE_TOKEN',
  'REPORTING_SERVICE_TOKEN',
  'BILLING_SERVICE_TOKEN',
  'AUDIT_SERVICE_TOKEN',
  'NOTIFICATION_SERVICE_TOKEN',
]) {
  process.env[name] = `${name.toLowerCase()}-test`;
}
process.env.GATEWAY_AUTH_MODE = 'enforce';
process.env.GATEWAY_CLOUD_RUN_AUTH = 'never';
process.env.GATEWAY_CORS_ALLOWED_ORIGINS = 'https://app.custoking.com';
process.env.GATEWAY_MAX_BODY_BYTES = '1024';

const {
  server,
  routes,
  requiresUserAuth,
  outboundHeaders,
  isRequestHopHeader,
  isResponseHopHeader,
  isClientSpoofableHeader,
  stringOrEmpty,
  setSecurityHeaders,
  isOriginAllowed,
  applyCors,
  clientIp,
  rateLimitKey,
  checkRateLimit,
  bodyTooLarge,
  verifyJwtLocally,
  principalFromClaims,
} = require('./server');

test.after(() => {
  if (server.listening) {
    server.close();
  }
});

test('gateway health endpoint returns service status without upstream access', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/gateway-health`);
  const payload = await response.json();

  assert.equal(response.status, 200);
  assert.deepEqual(payload, { status: 'UP', service: 'custoking-api-gateway' });
});

test('protected API route returns unauthorized before proxying without bearer token', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/api/v1/students`);
  const payload = await response.json();

  assert.equal(response.status, 401);
  assert.deepEqual(payload, { message: 'Unauthorized' });
});

test('unknown API route returns gateway route-not-configured response', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/api/v1/unknown-resource`);
  const payload = await response.json();

  assert.equal(response.status, 404);
  assert.deepEqual(payload, { message: 'No service route is configured for this API path' });
});

test('route table sends class-section student path to student service', () => {
  const matched = routes.find((candidate) => candidate.matches('/api/v1/classes/class-9/sections/a/students'));

  assert.equal(matched.service, 'student');
  assert.equal(matched.rewrite('/api/v1/classes/class-9/sections/a/students'), '/api/v1/classes/class-9/sections/a/students');
});

test('route table sends fee-structure subpaths to fee service without overmatching siblings', () => {
  const matched = routes.find((candidate) => candidate.matches('/api/v1/fee-structure/item'));
  const sibling = routes.find((candidate) => candidate.matches('/api/v1/fee-structurex/item'));

  assert.equal(matched.service, 'fee');
  assert.equal(matched.rewrite('/api/v1/fee-structure/item'), '/api/v1/fee-structure/item');
  assert.equal(sibling, undefined);
});

test('diagnostic route rewrites service-prefixed path to internal api path', () => {
  const matched = routes.find((candidate) => candidate.matches('/reporting-api/v1/dashboard'));

  assert.equal(matched.service, 'reporting');
  assert.equal(matched.rewrite('/reporting-api/v1/dashboard'), '/api/v1/dashboard');
});

test('auth classifier treats login refresh and logout as public auth routes', () => {
  assert.equal(requiresUserAuth('/api/v1/auth/login'), false);
  assert.equal(requiresUserAuth('/api/v1/auth/refresh'), false);
  assert.equal(requiresUserAuth('/api/v1/auth/logout'), false);
  assert.equal(requiresUserAuth('/api/v1/auth/introspect'), true);
  assert.equal(requiresUserAuth('/reporting-api/v1/dashboard'), true);
  assert.equal(requiresUserAuth('/assets/index.js'), false);
});

test('outbound headers strip hop-by-hop request headers and add forwarding metadata', () => {
  const headers = outboundHeaders({
    headers: {
      host: 'gateway.local',
      connection: 'keep-alive',
      authorization: 'Bearer user-token',
      'x-forwarded-for': '10.0.0.1',
      'x-forwarded-proto': 'http',
    },
    socket: {
      remoteAddress: '10.0.0.2',
    },
  }, 'request-1');

  assert.equal(headers.host, undefined);
  assert.equal(headers.connection, undefined);
  assert.equal(headers.authorization, 'Bearer user-token');
  assert.equal(headers['x-request-id'], 'request-1');
  assert.equal(headers['x-forwarded-for'], '10.0.0.1, 10.0.0.2');
  assert.equal(headers['x-forwarded-proto'], 'http');
});

test('hop header helpers classify request and response headers', () => {
  assert.equal(isRequestHopHeader('Connection'), true);
  assert.equal(isRequestHopHeader('X-Request-Id'), false);
  assert.equal(isResponseHopHeader('content-length'), true);
  assert.equal(isResponseHopHeader('content-type'), false);
});

test('stringOrEmpty normalizes nullable principal fields', () => {
  assert.equal(stringOrEmpty(null), '');
  assert.equal(stringOrEmpty(undefined), '');
  assert.equal(stringOrEmpty(4), '4');
});

test('school admin + operations-user route to identity, not tenant', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/schools/12/admin'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/operations-user'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/modules'), 'tenant'); // unchanged
  assert.equal(resolve('/api/v1/schools'), 'tenant');            // unchanged
});

test('student-review-items routes to student', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/student-review-items/RV-9'), 'student');
});

test('zone admin routes to identity, zone reads stay tenant', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/zones/12/admin'), 'identity');
  assert.equal(resolve('/api/v1/zones/12/admins'), 'tenant');  // plural list must NOT be captured
  assert.equal(resolve('/api/v1/zones'), 'tenant');
});

test('user directory routes to identity for both exact and sub-paths', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/users'), 'identity');             // exact collection path (UsersPage list)
  assert.equal(resolve('/api/v1/users/7'), 'identity');           // detail
  assert.equal(resolve('/api/v1/users/7/disable'), 'identity');   // command sub-path
  assert.equal(resolve('/api/v1/users/provisioning/schools/1/users/ADMIN'), 'identity');
});

test('workspace firefighting routes to firefighting, not reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/workspace/firefighting'), 'firefighting');
  assert.equal(resolve('/api/v1/workspace/students'), 'student');
  assert.equal(resolve('/api/v1/workspace'), 'reporting');
});

test('workspace staff routes to tenant, not reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/workspace/staff'), 'tenant');
  assert.equal(resolve('/api/v1/workspace'), 'reporting');
});

test('workspace timetable writes route to tenant, workspace reads stay reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/workspace/timetable'), 'tenant');
  assert.equal(resolve('/api/v1/workspace'), 'reporting');
});

test('vendor-dues mark-paid routes to owning services, dashboard reads stay reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/dashboard/vendor-dues/catalog-orders/12/mark-paid'), 'catalog');
  assert.equal(resolve('/api/v1/dashboard/vendor-dues/firefighting/FF-3/mark-paid'), 'firefighting');
  assert.equal(resolve('/api/v1/dashboard/vendor-dues'), 'reporting');
});

test('fee-defaulter reminders route to fee, defaulter reads stay reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/dashboard/finance/fee-defaulters/reminders'), 'fee');
  assert.equal(resolve('/api/v1/dashboard/finance/fee-defaulters'), 'reporting');
});

test('school-facing billing compatibility routes to billing without stealing fee payments', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/customers'), 'billing');
  assert.equal(resolve('/api/v1/invoices/12/pdf'), 'billing');
  assert.equal(resolve('/api/v1/billing-payments'), 'billing');
  assert.equal(resolve('/api/v1/payments'), 'fee');
});

test('legacy approvals inbox routes to reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/approvals'), 'reporting');
  assert.equal(resolve('/api/v1/approvals/catalog:CK-1001/approve'), 'reporting');
});

test('security headers are present on responses', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/gateway-health`);
  await response.text();

  assert.match(response.headers.get('strict-transport-security') || '', /max-age=\d+/);
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('x-frame-options'), 'DENY');
  assert.equal(response.headers.get('referrer-policy'), 'strict-origin-when-cross-origin');
  assert.match(response.headers.get('content-security-policy') || '', /frame-ancestors 'none'/);
});

test('preflight from an allow-listed origin is approved with credentials', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/api/v1/students`, {
    method: 'OPTIONS',
    headers: {
      origin: 'https://app.custoking.com',
      'access-control-request-method': 'GET',
    },
  });
  await response.text();

  assert.equal(response.status, 204);
  assert.equal(response.headers.get('access-control-allow-origin'), 'https://app.custoking.com');
  assert.equal(response.headers.get('access-control-allow-credentials'), 'true');
  assert.notEqual(response.headers.get('access-control-allow-origin'), '*');
});

test('preflight from a disallowed origin is blocked', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/api/v1/students`, {
    method: 'OPTIONS',
    headers: {
      origin: 'https://evil.example.com',
      'access-control-request-method': 'GET',
    },
  });
  const payload = await response.json();

  assert.equal(response.status, 403);
  assert.equal(response.headers.get('access-control-allow-origin'), null);
  assert.deepEqual(payload, { message: 'Origin not allowed' });
});

test('oversized request body is rejected with 413 before reaching an upstream', async () => {
  const baseUrl = await listen();

  const response = await fetch(`${baseUrl}/api/v1/students`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: 'x'.repeat(2048), // exceeds GATEWAY_MAX_BODY_BYTES=1024
  });
  const payload = await response.json();

  assert.equal(response.status, 413);
  assert.deepEqual(payload, { message: 'Payload too large' });
});

test('cors helpers classify origins against the allowlist', () => {
  assert.equal(isOriginAllowed('https://app.custoking.com'), true);
  assert.equal(isOriginAllowed('https://evil.example.com'), false);
  assert.equal(isOriginAllowed(undefined), false);

  const setHeaders = {};
  const fakeRes = { setHeader: (k, v) => { setHeaders[k] = v; } };
  assert.equal(applyCors({ headers: {} }, fakeRes), 'none');
  assert.equal(applyCors({ headers: { origin: 'https://evil.example.com' } }, fakeRes), 'blocked');
  assert.equal(applyCors({ headers: { origin: 'https://app.custoking.com' } }, fakeRes), 'allowed');
  assert.equal(setHeaders['Access-Control-Allow-Origin'], 'https://app.custoking.com');
});

test('bodyTooLarge honours the configured content-length limit', () => {
  assert.equal(bodyTooLarge({ headers: { 'content-length': '512' } }), false);
  assert.equal(bodyTooLarge({ headers: { 'content-length': '4096' } }), true);
  assert.equal(bodyTooLarge({ headers: {} }), false);
});

test('rate-limit key prefers bearer token, falls back to forwarded client IP', () => {
  assert.equal(rateLimitKey({ headers: { authorization: 'Bearer abc.def' }, socket: {} }), 'tok:abc.def');
  assert.equal(rateLimitKey({ headers: { 'x-forwarded-for': '10.0.0.5, 10.0.0.1' }, socket: {} }), 'ip:10.0.0.5');
  assert.equal(clientIp({ headers: {}, socket: { remoteAddress: '10.0.0.9' } }), '10.0.0.9');
});

test('token-bucket limiter allows up to burst, then denies, then refills', () => {
  const req = { headers: { authorization: 'Bearer rl-token' }, socket: {} };
  const buckets = new Map();
  const base = 1_000_000;

  assert.equal(checkRateLimit(req, { rps: 1, burst: 2, buckets, now: base }).allowed, true);
  assert.equal(checkRateLimit(req, { rps: 1, burst: 2, buckets, now: base }).allowed, true);

  const denied = checkRateLimit(req, { rps: 1, burst: 2, buckets, now: base });
  assert.equal(denied.allowed, false);
  assert.ok(denied.retryAfter >= 1);

  // One second later a single token has refilled.
  assert.equal(checkRateLimit(req, { rps: 1, burst: 2, buckets, now: base + 1000 }).allowed, true);
  assert.equal(checkRateLimit(req, { rps: 1, burst: 2, buckets, now: base + 1000 }).allowed, false);
});

test('rate limiter is disabled when rps is zero', () => {
  const req = { headers: {}, socket: { remoteAddress: '10.0.0.1' } };
  const buckets = new Map();
  for (let i = 0; i < 5; i += 1) {
    assert.equal(checkRateLimit(req, { rps: 0, burst: 0, buckets, now: 1 }).allowed, true);
  }
});

test('outbound headers strip client-supplied authenticated and service-token headers', () => {
  const headers = outboundHeaders({
    headers: {
      'x-authenticated-school-id': '99',
      'x-authenticated-role': 'SUPERADMIN',
      'x-identity-service-token': 'forged',
      'x-student-service-token': 'forged',
      'content-type': 'application/json',
      authorization: 'Bearer user-token',
    },
    socket: { remoteAddress: '10.0.0.2' },
  }, 'req-1');

  assert.equal(headers['x-authenticated-school-id'], undefined);
  assert.equal(headers['x-authenticated-role'], undefined);
  assert.equal(headers['x-identity-service-token'], undefined);
  assert.equal(headers['x-student-service-token'], undefined);
  // non-spoofable headers are preserved:
  assert.equal(headers['content-type'], 'application/json');
  assert.equal(headers.authorization, 'Bearer user-token');
});

test('isClientSpoofableHeader flags gateway-only headers', () => {
  assert.equal(isClientSpoofableHeader('X-Authenticated-School-Id'), true);
  assert.equal(isClientSpoofableHeader('x-authenticated-role'), true);
  assert.equal(isClientSpoofableHeader('X-Identity-Service-Token'), true);
  assert.equal(isClientSpoofableHeader('x-billing-service-token'), true);
  assert.equal(isClientSpoofableHeader('content-type'), false);
  assert.equal(isClientSpoofableHeader('x-request-id'), false);
});

// --- Local JWT verification (Task 2.3) ---

function signHS512(payload, secret, header = { alg: 'HS512', typ: 'JWT' }) {
  const h = Buffer.from(JSON.stringify(header)).toString('base64url');
  const p = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.createHmac('sha512', secret).update(`${h}.${p}`).digest('base64url');
  return `${h}.${p}.${sig}`;
}

const JWT_SECRET = 'test-jwt-secret-at-least-32-characters-long';
const NOW = 1_000_000;
const enrichedClaims = { sub: 'a@b.com', role: 'ADMIN', uid: 42, sid: 7, zid: 3, ver: 2, exp: NOW + 900 };

test('verifyJwtLocally accepts a valid enriched HS512 token', () => {
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const claims = verifyJwtLocally(token, JWT_SECRET, NOW);
  assert.equal(claims.uid, 42);
  assert.equal(claims.ver, 2);
});

test('verifyJwtLocally rejects a tampered signature', () => {
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const tampered = `${token.slice(0, -2)}xx`;
  assert.equal(verifyJwtLocally(tampered, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects a token signed with a different secret', () => {
  const token = signHS512(enrichedClaims, 'some-other-secret-key-32-characters-x');
  assert.equal(verifyJwtLocally(token, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects an expired token', () => {
  const token = signHS512({ ...enrichedClaims, exp: NOW - 1 }, JWT_SECRET);
  assert.equal(verifyJwtLocally(token, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects alg none and alg RS256', () => {
  const none = signHS512(enrichedClaims, JWT_SECRET, { alg: 'none', typ: 'JWT' });
  const rs = signHS512(enrichedClaims, JWT_SECRET, { alg: 'RS256', typ: 'JWT' });
  assert.equal(verifyJwtLocally(none, JWT_SECRET, NOW), null);
  assert.equal(verifyJwtLocally(rs, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects a malformed token', () => {
  assert.equal(verifyJwtLocally('a.b', JWT_SECRET, NOW), null);
  assert.equal(verifyJwtLocally('not-a-token', JWT_SECRET, NOW), null);
});

test('principalFromClaims maps an enriched claim set', () => {
  assert.deepEqual(principalFromClaims(enrichedClaims), {
    userId: 42, email: 'a@b.com', role: 'ADMIN', branchId: 7, zoneId: 3,
  });
});

test('principalFromClaims returns null for un-enriched (ver<2 / absent) claims', () => {
  assert.equal(principalFromClaims({ sub: 'a@b.com', role: 'ADMIN' }), null);
  assert.equal(principalFromClaims({ ...enrichedClaims, ver: 1 }), null);
});

test('principalFromClaims yields null branchId/zoneId when sid/zid absent', () => {
  const p = principalFromClaims({ sub: 's@b.com', role: 'SUPERADMIN', uid: 1, ver: 2 });
  assert.equal(p.branchId, null);
  assert.equal(p.zoneId, null);
});

async function listen() {
  if (!server.listening) {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  }
  const address = server.address();
  return `http://${address.address}:${address.port}`;
}
