'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const { Readable } = require('node:stream');
const { context, trace } = require('@opentelemetry/api');

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
process.env.OTEL_SDK_DISABLED = 'true';
process.env.GCP_PROJECT = 'custoking-test';

const {
  server,
  routes,
  requiresUserAuth,
  isCookieAuthPath,
  outboundHeaders,
  isRequestHopHeader,
  isResponseHopHeader,
  isClientSpoofableHeader,
  stringOrEmpty,
  setSecurityHeaders,
  isOriginAllowed,
  applyCors,
  clientIp,
  parseBearerToken,
  buildUpstreamTarget,
  rateLimitKey,
  checkRateLimit,
  bodyTooLarge,
  boundedRequestBody,
  isPayloadTooLargeError,
  currentTraceFields,
  cloudLoggingTraceFields,
  parseTraceparentHeader,
  verifyJwtLocally,
  principalFromClaims,
  authenticate,
  proxyToUrl,
} = require('./server');
const {
  configuredResourceAttributes,
  flushTracing,
  tracesEndpoint,
} = require('./tracing');
const { inventory } = require('./api-contract');

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

test('gateway tracing preserves service, environment, version, and project resource attributes', () => {
  process.env.OTEL_SERVICE_NAME = 'api-gateway';
  const attributes = configuredResourceAttributes(
    'gcp.project_id=custoking,deployment.environment.name=dev,service.version=abc123',
  );

  assert.deepEqual(attributes, {
    'gcp.project_id': 'custoking',
    'deployment.environment.name': 'dev',
    'service.version': 'abc123',
    'service.name': 'api-gateway',
  });
  process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = 'https://telemetry.googleapis.com/v1/traces';
  assert.equal(tracesEndpoint(), 'https://telemetry.googleapis.com/v1/traces');
});

test('gateway trace flush is a safe no-op when tracing is disabled', async () => {
  await assert.doesNotReject(flushTracing());
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

test('every compatibility controller mapping remains reachable through the owning gateway route', () => {
  const owningGatewayService = {
    BillingPublicCompatibilityController: 'billing',
    IdentityPublicCompatibilityController: 'identity',
    FirefightingPublicCompatibilityController: 'firefighting',
    AuditPublicCompatibilityController: 'audit',
    ReportingApprovalsCompatibilityController: 'reporting',
    ReportingPublicCompatibilityController: 'reporting',
    CatalogPublicCompatibilityController: 'catalog',
    FeePublicCompatibilityController: 'fee',
    StudentWorkspaceCompatibilityController: 'student',
    TenantSchoolPublicCompatibilityController: 'tenant',
  };

  for (const endpoint of inventory.endpoints.filter((candidate) => candidate.classification === 'compatibility')) {
    const samplePath = endpoint.path.replace(/\{[^}]+\}/g, 'sample');
    const matched = routes.find((candidate) => candidate.matches(samplePath, endpoint.method));
    assert.ok(matched, `${endpoint.method} ${endpoint.path} is not exposed by the gateway`);
    assert.equal(matched.service, owningGatewayService[endpoint.controller],
      `${endpoint.method} ${endpoint.path} routes to ${matched.service}`);
  }
});

test('cookie-auth classifier is limited to refresh and logout routes', () => {
  assert.equal(isCookieAuthPath('/api/v1/auth/refresh'), true);
  assert.equal(isCookieAuthPath('/api/v1/auth/logout/session'), true);
  assert.equal(isCookieAuthPath('/api/v1/auth/login'), false);
  assert.equal(isCookieAuthPath('/api/v1/auth/refresh-token'), false);
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

test('outbound headers replace inbound traceparent with the active gateway span context', () => {
  const spanContext = {
    traceId: '0af7651916cd43dd8448eb211c80319c',
    spanId: 'b7ad6b7169203331',
    traceFlags: 1,
  };
  const span = trace.wrapSpanContext(spanContext);

  const activeContext = trace.setSpan(context.active(), span);
  const headers = outboundHeaders({
    headers: {
      traceparent: '00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01',
    },
    socket: { remoteAddress: '10.0.0.2' },
  }, 'request-1', activeContext);

  assert.equal(headers.traceparent, '00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01');
});

test('trace helpers expose Cloud Logging trace correlation fields', () => {
  const spanContext = {
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    spanId: '00f067aa0ba902b7',
    traceFlags: 1,
  };
  const span = trace.wrapSpanContext(spanContext);

  const activeContext = trace.setSpan(context.active(), span);
  const fields = currentTraceFields({ headers: {} }, activeContext);
  assert.deepEqual(fields, spanContext);
  assert.deepEqual(cloudLoggingTraceFields(fields), {
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    spanId: '00f067aa0ba902b7',
    'logging.googleapis.com/trace': 'projects/custoking-test/traces/4bf92f3577b34da6a3ce929d0e0e4736',
    'logging.googleapis.com/spanId': '00f067aa0ba902b7',
  });
});

test('trace helpers fall back to a valid incoming traceparent header', () => {
  assert.deepEqual(parseTraceparentHeader('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'), {
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    spanId: '00f067aa0ba902b7',
    traceFlags: 1,
  });
  assert.equal(parseTraceparentHeader('00-00000000000000000000000000000000-00f067aa0ba902b7-01'), null);
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

test('school admin reads route to tenant and provisioning writes route to identity', () => {
  const resolve = (p, method = 'GET') => routes.find((r) => r.matches(p, method))?.service;
  assert.equal(resolve('/api/v1/schools/12/admin', 'GET'), 'tenant');
  assert.equal(resolve('/api/v1/schools/12/admin', 'POST'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/operations-user', 'POST'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/modules'), 'tenant'); // unchanged
  assert.equal(resolve('/api/v1/schools'), 'tenant');            // unchanged
});

test('student-review-items routes to student', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/student-review-items/RV-9'), 'student');
});

test('timetable routes to tenant for both the bare grid path and subpaths', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/timetable'), 'tenant');                 // grid fetch: GET /timetable?sectionId=..
  assert.equal(resolve('/api/v1/timetable/bell-schedules'), 'tenant');  // subpath
  assert.equal(resolve('/api/v1/timetable/entry'), 'tenant');           // subpath
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

test('timetable API routes to tenant, workspace reads stay reporting', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/timetable/entry'), 'tenant');
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
      'access-control-request-method': 'DELETE',
      'access-control-request-headers': 'x-student-delete-confirmation',
    },
  });
  await response.text();

  assert.equal(response.status, 204);
  assert.equal(response.headers.get('access-control-allow-origin'), 'https://app.custoking.com');
  assert.equal(response.headers.get('access-control-allow-credentials'), 'true');
  assert.match(response.headers.get('access-control-allow-headers') || '', /X-Student-Delete-Confirmation/i);
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

test('ordinary refresh and logout requests from a disallowed origin are blocked', async () => {
  const baseUrl = await listen();

  for (const path of ['/api/v1/auth/refresh', '/api/v1/auth/logout']) {
    const response = await fetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers: {
        origin: 'https://evil.example.com',
        'content-type': 'application/json',
      },
      body: '{}',
    });
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('access-control-allow-origin'), null);
    assert.deepEqual(await response.json(), { message: 'Origin not allowed' });
  }
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

test('bounded request body counts actual stream bytes without relying on content-length', async () => {
  const body = Readable.from([Buffer.alloc(600), Buffer.alloc(500)]);
  const received = [];

  await assert.rejects(async () => {
    for await (const chunk of boundedRequestBody(body, 1024)) received.push(chunk);
  }, (error) => isPayloadTooLargeError(error));
  assert.equal(Buffer.concat(received).length, 600);
});

test('bounded request body can be disabled explicitly', async () => {
  const received = [];
  for await (const chunk of boundedRequestBody(Readable.from([Buffer.alloc(2048)]), 0)) {
    received.push(chunk);
  }
  assert.equal(Buffer.concat(received).length, 2048);
});

test('proxy rejects a chunked upload when actual bytes exceed the configured limit', async () => {
  let upstreamBytes = 0;
  const upstream = http.createServer(async (req, res) => {
    try {
      for await (const chunk of req) upstreamBytes += chunk.length;
      res.end('ok');
    } catch {
      // The gateway deliberately aborts the incomplete upstream upload.
    }
  });
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve));

  try {
    const req = Readable.from([Buffer.alloc(600), Buffer.alloc(500)]);
    req.method = 'POST';
    req.headers = { 'transfer-encoding': 'chunked' };
    req.socket = { remoteAddress: '127.0.0.1' };
    const res = { setHeader() {}, write() {}, end() {} };
    const address = upstream.address();

    await assert.rejects(
      proxyToUrl(
        req,
        res,
        new URL(`http://127.0.0.1:${address.port}/upload`),
        'chunked-request',
        null,
        null,
        { maxBodyBytes: 1024 },
      ),
      (error) => isPayloadTooLargeError(error),
    );
    assert.ok(upstreamBytes <= 1024);
  } finally {
    await new Promise((resolve) => upstream.close(resolve));
  }
});

test('bearer parsing is strict and deterministic for untrusted authorization headers', () => {
  assert.equal(parseBearerToken('Bearer abc.def-_~+/='), 'abc.def-_~+/=');
  assert.equal(parseBearerToken('bearer token'), 'token');
  assert.equal(parseBearerToken('Bearer'), null);
  assert.equal(parseBearerToken('Bearer  token'), 'token');
  assert.equal(parseBearerToken('Bearer\ttoken'), null);
  assert.equal(parseBearerToken(`Bearer ${' '.repeat(100_000)}`), null);
  assert.equal(parseBearerToken(`Bearer ${'a'.repeat(8193)}`), null);
  assert.equal(parseBearerToken(undefined), null);
});

test('proxy preserves the permanent-delete confirmation header and authenticated school context', async () => {
  let received = null;
  const upstream = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    received = {
      method: req.method,
      path: req.url,
      confirmation: req.headers['x-student-delete-confirmation'],
      serviceToken: req.headers['x-student-service-token'],
      schoolId: req.headers['x-authenticated-school-id'],
      permissions: req.headers['x-authenticated-permissions'],
      body: Buffer.concat(chunks).toString('utf8'),
    };
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"deleted":true}');
  });
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve));

  try {
    const req = Readable.from([]);
    req.method = 'DELETE';
    req.headers = {
      'x-student-delete-confirmation': 'ADM-42',
    };
    req.socket = { remoteAddress: '127.0.0.1' };

    const responseChunks = [];
    const responseHeaders = {};
    const res = {
      statusCode: 0,
      setHeader(name, value) { responseHeaders[name.toLowerCase()] = value; },
      write(chunk) { responseChunks.push(Buffer.from(chunk)); },
      end() {},
    };
    const address = upstream.address();
    const target = new URL(`http://127.0.0.1:${address.port}/api/v1/students/42`);

    await proxyToUrl(req, res, target, 'delete-request', 'student', {
      userId: 7,
      email: 'admin@example.test',
      role: 'ADMIN',
      branchId: 10,
      zoneId: null,
      permissions: ['student:delete'],
      operatorSchools: [],
    });

    assert.equal(res.statusCode, 200);
    assert.equal(responseHeaders['content-type'], 'application/json');
    assert.deepEqual(JSON.parse(Buffer.concat(responseChunks).toString('utf8')), { deleted: true });
    assert.deepEqual(received, {
      method: 'DELETE',
      path: '/api/v1/students/42',
      confirmation: 'ADM-42',
      serviceToken: 'student_service_token-test',
      schoolId: '10',
      permissions: 'student:delete',
      body: '',
    });
  } finally {
    await new Promise((resolve) => upstream.close(resolve));
  }
});

test('proxy targets retain the configured upstream origin and canonicalize untrusted path/query data', () => {
  const upstream = new URL('https://school-core.example.test');
  const target = buildUpstreamTarget(
    upstream,
    '/api/v1/approvals/catalog:CK%201',
    '?schoolId=7&next=https%3A%2F%2Fevil.example%2Finternal',
  );

  assert.equal(target.origin, upstream.origin);
  assert.equal(target.pathname, '/api/v1/approvals/catalog%3ACK%201');
  assert.equal(target.searchParams.get('schoolId'), '7');
  assert.equal(target.searchParams.get('next'), 'https://evil.example/internal');

  const networkPath = buildUpstreamTarget(upstream, '//metadata.google.internal/computeMetadata/v1');
  assert.equal(networkPath.origin, upstream.origin);
  assert.equal(networkPath.pathname, '//metadata.google.internal/computeMetadata/v1');
});

test('proxy targets reject encoded traversal and path-separator segments', () => {
  const upstream = new URL('https://school-core.example.test');

  assert.throws(() => buildUpstreamTarget(upstream, '/api/%2e%2e/admin'), /forbidden segment/);
  assert.throws(() => buildUpstreamTarget(upstream, '/api/%2Fadmin'), /forbidden segment/);
  assert.throws(() => buildUpstreamTarget(upstream, '/api/%ZZ'), /invalid percent encoding/);
  assert.throws(() => buildUpstreamTarget(upstream, 'api/v1/students'), /must be absolute/);
});

test('rate-limit key prefers a valid bearer token, falls back to forwarded client IP', () => {
  const tokenKey = rateLimitKey({ headers: { authorization: 'Bearer abc.def' }, socket: {} });
  assert.match(tokenKey, /^tok-sha256:[A-Za-z0-9_-]{43}$/);
  assert.equal(tokenKey.includes('abc.def'), false);
  assert.equal(rateLimitKey({ headers: { authorization: 'Bearer  abc.def', 'x-forwarded-for': '10.0.0.6' }, socket: {} }), tokenKey);
  assert.equal(rateLimitKey({ headers: { 'x-forwarded-for': '10.0.0.5, 10.0.0.1' }, socket: {} }), 'ip:10.0.0.5');
  assert.equal(rateLimitKey({ headers: { authorization: `Bearer ${'a'.repeat(8193)}`, 'x-forwarded-for': '10.0.0.8' }, socket: {} }), 'ip:10.0.0.8');
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

test('rate limiter never exceeds its configured key bound under active-key churn', () => {
  const buckets = new Map();
  const request = (token) => ({ headers: { authorization: `Bearer ${token}` }, socket: {} });

  checkRateLimit(request('one'), { rps: 1, burst: 2, buckets, maxKeys: 2, now: 1000 });
  checkRateLimit(request('two'), { rps: 1, burst: 2, buckets, maxKeys: 2, now: 1001 });
  checkRateLimit(request('three'), { rps: 1, burst: 2, buckets, maxKeys: 2, now: 1002 });

  assert.equal(buckets.size, 2);
  assert.equal([...buckets.keys()].some((key) => key.includes('one') || key.includes('two') || key.includes('three')), false);
  assert.equal(buckets.has(rateLimitKey(request('one'))), false);
  assert.equal(buckets.has(rateLimitKey(request('three'))), true);
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
  assert.equal(isClientSpoofableHeader('x-authenticated-permissions'), true);
  assert.equal(isClientSpoofableHeader('x-authenticated-operator-schools'), true);
  assert.equal(isClientSpoofableHeader('content-type'), false);
  assert.equal(isClientSpoofableHeader('x-request-id'), false);
});

// --- Local JWT verification (Task 2.3) ---

const TEST_HMAC_DIGESTS = {
  HS256: 'sha256',
  HS384: 'sha384',
  HS512: 'sha512',
};

function signJwt(payload, secret, header = { alg: 'HS512', typ: 'JWT' }) {
  const h = Buffer.from(JSON.stringify(header)).toString('base64url');
  const p = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto
    .createHmac(TEST_HMAC_DIGESTS[header.alg] || 'sha512', secret)
    .update(`${h}.${p}`)
    .digest('base64url');
  return `${h}.${p}.${sig}`;
}

function signHS512(payload, secret, header = { alg: 'HS512', typ: 'JWT' }) {
  return signJwt(payload, secret, header);
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

test('student photo import and assigned-school export routes go to student service', () => {
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/student-photo-imports/context'), 'student');
  assert.equal(resolve('/api/v1/student-photo-imports/8c1f/scan'), 'student');
  assert.equal(resolve('/api/v1/students/export/context'), 'student');
  assert.equal(resolve('/api/v1/students/export/archive'), 'student');
  assert.equal(resolve('/api/v1/guardian-data-review/summary'), undefined);
});

test('verifyJwtLocally accepts a valid enriched HS256 token', () => {
  const token = signJwt(enrichedClaims, JWT_SECRET, { alg: 'HS256', typ: 'JWT' });
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

test('verifyJwtLocally rejects a 3-segment token whose payload is not valid JSON', () => {
  const header = Buffer.from(JSON.stringify({ alg: 'HS512', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from('not-json-at-all').toString('base64url');
  const sig = crypto.createHmac('sha512', JWT_SECRET).update(`${header}.${payload}`).digest('base64url');
  assert.equal(verifyJwtLocally(`${header}.${payload}.${sig}`, JWT_SECRET, NOW), null);
});

test('principalFromClaims maps an enriched claim set', () => {
  assert.deepEqual(principalFromClaims(enrichedClaims), {
    userId: 42, email: 'a@b.com', role: 'ADMIN', branchId: 7, zoneId: 3, permissions: [], operatorSchools: [],
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

test('principalFromClaims exposes permissions for ver>=3', () => {
  const p = principalFromClaims({ ver: 3, uid: 7, sub: 'a@x', role: 'ADMIN', perms: ['firefighting:approve'] });
  assert.deepEqual(p.permissions, ['firefighting:approve']);
});

test('principalFromClaims defaults permissions to [] for ver 2', () => {
  const p = principalFromClaims({ ver: 2, uid: 7, sub: 'a@x', role: 'ADMIN' });
  assert.deepEqual(p.permissions, []);
});

test('principalFromClaims exposes operatorSchools for ver>=3', () => {
  const p = principalFromClaims({ ver: 3, uid: 7, sub: 'a@x', role: 'OPERATIONS', ops_schools: [2, 3] });
  assert.deepEqual(p.operatorSchools, [2, 3]);
});

test('principalFromClaims defaults operatorSchools to [] when ops_schools absent', () => {
  const p = principalFromClaims({ ver: 2, uid: 7, sub: 'a@x', role: 'ADMIN' });
  assert.deepEqual(p.operatorSchools, []);
});

// --- authenticate() dispatch (Task 2.3) ---

function reqWithToken(token) {
  return { headers: token ? { authorization: `Bearer ${token}` } : {} };
}

test('authenticate uses local claims for an enriched token and does NOT introspect', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 999 }; };
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const principal = await authenticate(reqWithToken(token), 'req-1', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal.userId, 42);
  assert.equal(principal.branchId, 7);
});

test('authenticate falls back to introspection for a valid un-enriched token', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 5, branchId: 8 }; };
  const legacy = signHS512({ sub: 'a@b.com', role: 'ADMIN', exp: NOW + 900 }, JWT_SECRET);
  const principal = await authenticate(reqWithToken(legacy), 'req-2', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 1);
  assert.equal(principal.userId, 5);
});

test('authenticate rejects a refresh token without introspection', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 5 }; };
  const refresh = signHS512({ sub: 'a@b.com', role: 'ADMIN', type: 'refresh', exp: NOW + 900 }, JWT_SECRET);
  const principal = await authenticate(reqWithToken(refresh), 'req-refresh', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal, null);
});

test('authenticate returns null (no introspection) for a bad-signature token', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 1 }; };
  const bad = `${signHS512(enrichedClaims, JWT_SECRET).slice(0, -2)}xx`;
  const principal = await authenticate(reqWithToken(bad), 'req-3', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal, null);
});

test('authenticate always introspects when local verify is disabled', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 77 }; };
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const principal = await authenticate(reqWithToken(token), 'req-4', {
    localVerify: false, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 1);
  assert.equal(principal.userId, 77);
});

test('authenticate returns null and does not introspect when no bearer token is present', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 1 }; };
  const principal = await authenticate(reqWithToken(null), 'req-5', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal, null);
});

async function listen() {
  if (!server.listening) {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  }
  const address = server.address();
  return `http://${address.address}:${address.port}`;
}
