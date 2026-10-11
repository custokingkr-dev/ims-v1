'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createAuthentication } = require('./authentication');
const { createQuotaControls } = require('./quota');
const { createProxyTransport } = require('./transport');

const quotaConfig = { MAX_BEARER_TOKEN_BYTES: 128, RATE_LIMIT_RPS: 1, RATE_LIMIT_BURST: 1, RATE_LIMIT_MAX_KEYS: 2, TRUSTED_PROXY_HOPS: 0 };
const req = () => ({ headers: { authorization: 'Bearer fixture-token' }, socket: { remoteAddress: '127.0.0.1' }, method: 'GET', url: '/api/v1/students?schoolId=7' });

test('quota instances own independent bounded state and associations do not authenticate', () => {
  const first = createQuotaControls(quotaConfig);
  const second = createQuotaControls(quotaConfig);
  assert.equal(first.checkRateLimit(req(), { now: 1 }).allowed, true);
  assert.equal(first.checkRateLimit(req(), { now: 1 }).allowed, false);
  assert.equal(second.checkRateLimit(req(), { now: 1 }).allowed, true);
  first.rememberVerifiedRatePrincipal(req(), { userId: 7 }, { now: 1 });
  assert.equal(first.preAuthRateLimitKey(req(), { now: 2 }), 'verified-user:7');
  assert.equal(second.preAuthRateLimitKey(req(), { now: 2 }), 'ip:127.0.0.1');
});

test('authentication factory always uses current identity and passes only bounded operation context', async () => {
  const requests = [];
  const auth = createAuthentication({ LOCAL_JWT_VERIFY: false, APP_JWT_SECRET: '',
    upstreams: { identity: new URL('https://identity.example') },
    tokenHeaders: { identity: 'X-Identity-Service-Token' }, serviceTokens: { identity: 'fixture-identity-token' },
    parseBearerToken: createQuotaControls(quotaConfig).parseBearerToken,
    addCloudRunAuthorization: async headers => { headers.authorization = 'Bearer fixture-oidc'; },
    fetchFn: async (target, init) => {
      requests.push({ target, init });
      return new Response(JSON.stringify({ active: requests.length === 1, principal: { userId: 7, permissions: [] } }));
    },
  });
  assert.deepEqual(await auth.authenticate(req(), 'first'), { userId: 7, permissions: [] });
  assert.equal(await auth.authenticate(req(), 'revoked'), null);
  assert.equal(requests.length, 2);
  assert.equal(requests[0].target.href, 'https://identity.example/api/v1/auth/introspect');
  assert.deepEqual(JSON.parse(requests[0].init.body), { token: 'fixture-token', method: 'GET', path: '/api/v1/students', schoolId: 7 });
  assert.equal(requests[0].init.headers.authorization, 'Bearer fixture-oidc');
});

test('transport factory rejects spoofed identity and shares its configured service credential only upstream', async () => {
  const seen = [];
  const transport = createProxyTransport({ upstreams: {}, tokenHeaders: { student: 'X-Student-Service-Token' },
    serviceTokens: { student: 'fixture-service-credential' }, CLOUD_RUN_AUTH: 'never', MAX_BODY_BYTES: 8,
    PROXY_TIMEOUT_MS: 1000, EXPORT_TIMEOUT_MS: 3000, AUTH_TIMEOUT_MS: 2000,
    activeSpanContext: () => null, formatTraceparent: () => '', sendJson: () => {},
    fetchFn: async (target, init) => { seen.push(init); return new Response(null, { status: 204, headers: { 'Permissions-Policy': 'camera=()' } }); },
  });
  const request = req();
  request.headers['x-authenticated-role'] = 'SUPERADMIN';
  request.headers['x-student-service-token'] = 'forged';
  const responseHeaders = new Map([['permissions-policy', 'camera=(self)']]);
  const response = { writableFinished: true, setHeader(name, value) { responseHeaders.set(name.toLowerCase(), value); }, end() {} };
  await transport.proxyToUrl(request, response, new URL('https://student.example/api/v1/students'), 'trace-request', 'student', { userId: 7, role: 'ADMIN', branchId: 10 });
  assert.equal(seen[0].headers['x-authenticated-role'], 'ADMIN');
  assert.equal(seen[0].headers['x-authenticated-school-id'], '10');
  assert.equal(seen[0].headers['X-Student-Service-Token'], 'fixture-service-credential');
  assert.equal(seen[0].headers['x-student-service-token'], undefined);
  assert.equal(seen[0].headers['x-request-id'], 'trace-request');
  assert.equal(transport.proxyDeadline('/api/v1/students/export'), 3000);
  assert.equal(response.statusCode, 204);
  assert.equal(responseHeaders.get('permissions-policy'), 'camera=()');
});
