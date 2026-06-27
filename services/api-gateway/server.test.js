'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

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

const {
  server,
  routes,
  requiresUserAuth,
  outboundHeaders,
  isRequestHopHeader,
  isResponseHopHeader,
  stringOrEmpty,
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

async function listen() {
  if (!server.listening) {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  }
  const address = server.address();
  return `http://${address.address}:${address.port}`;
}
