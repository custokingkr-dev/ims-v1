'use strict';

const http = require('http');
const crypto = require('crypto');
const { randomUUID } = require('crypto');

const PORT = Number(process.env.PORT || 80);
const AUTH_MODE = (process.env.GATEWAY_AUTH_MODE || 'enforce').toLowerCase();
const CLOUD_RUN_AUTH = (process.env.GATEWAY_CLOUD_RUN_AUTH || 'auto').toLowerCase();

// Task 2.3: verify the access token locally instead of introspecting per request.
// 'disabled' forces pure introspection (instant rollback lever, no redeploy).
const LOCAL_JWT_VERIFY = (process.env.GATEWAY_LOCAL_JWT_VERIFY || 'enabled').toLowerCase() !== 'disabled';
const APP_JWT_SECRET = process.env.APP_JWT_SECRET || '';
let warnedMissingJwtSecret = false;

// --- Edge security controls (Phase 0 / SEC-P0-2) ---
// Strict CORS: an explicit origin allowlist; never `*` together with credentials.
const CORS_ALLOWED_ORIGINS = (process.env.GATEWAY_CORS_ALLOWED_ORIGINS || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const CORS_ALLOW_METHODS = process.env.GATEWAY_CORS_ALLOW_METHODS || 'GET,POST,PUT,PATCH,DELETE,OPTIONS';
const CORS_ALLOW_HEADERS = process.env.GATEWAY_CORS_ALLOW_HEADERS || 'Authorization,Content-Type,X-Request-ID';
const CORS_MAX_AGE = process.env.GATEWAY_CORS_MAX_AGE || '600';

// Security response headers (overridable so deployments can tune the CSP for the SPA).
const CSP = process.env.GATEWAY_CSP
  || "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
  + "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
  + "frame-ancestors 'none'";
const HSTS = process.env.GATEWAY_HSTS || 'max-age=63072000; includeSubDomains; preload';
const REFERRER_POLICY = process.env.GATEWAY_REFERRER_POLICY || 'strict-origin-when-cross-origin';

// Max request body size in bytes (0 disables the check). Default 5 MiB.
const MAX_BODY_BYTES = Number(process.env.GATEWAY_MAX_BODY_BYTES || 5 * 1024 * 1024);

// Global token-bucket rate limit, keyed by bearer token (else client IP). 0 RPS disables it.
const RATE_LIMIT_RPS = Number(process.env.GATEWAY_RATE_LIMIT_RPS || 50);
const RATE_LIMIT_BURST = Number(process.env.GATEWAY_RATE_LIMIT_BURST || 100);
const RATE_LIMIT_MAX_KEYS = Number(process.env.GATEWAY_RATE_LIMIT_MAX_KEYS || 50_000);
const rateBuckets = new Map();

const upstreams = {
  frontend: envUrl('FRONTEND_UPSTREAM', 'http://frontend:80'),
  identity: envUrl('IDENTITY_UPSTREAM', 'http://identity-service:8080'),
  tenant: envUrl('TENANT_SCHOOL_UPSTREAM', 'http://school-core-service:8080'),
  student: envUrl('STUDENT_UPSTREAM', 'http://school-core-service:8080'),
  attendance: envUrl('ATTENDANCE_UPSTREAM', 'http://school-core-service:8080'),
  fee: envUrl('FEE_UPSTREAM', 'http://school-core-service:8080'),
  catalog: envUrl('CATALOG_UPSTREAM', 'http://school-core-service:8080'),
  // Phase 2: workflow + firefighting are served by the merged operations-service.
  workflow: envUrl('WORKFLOW_UPSTREAM', 'http://operations-service:8080'),
  firefighting: envUrl('FIREFIGHTING_UPSTREAM', 'http://operations-service:8080'),
  // Phase 2: reporting + notification + audit are served by the merged platform-service.
  reporting: envUrl('REPORTING_UPSTREAM', 'http://platform-service:8080'),
  billing: envUrl('BILLING_UPSTREAM', 'http://billing-service:8080'),
  audit: envUrl('AUDIT_UPSTREAM', 'http://platform-service:8080'),
  notification: envUrl('NOTIFICATION_UPSTREAM', 'http://platform-service:8080'),
};

const serviceTokens = {
  identity: requiredEnv('IDENTITY_SERVICE_TOKEN'),
  tenant: requiredEnv('TENANT_SCHOOL_SERVICE_TOKEN'),
  student: requiredEnv('STUDENT_SERVICE_TOKEN'),
  attendance: requiredEnv('ATTENDANCE_SERVICE_TOKEN'),
  fee: requiredEnv('FEE_SERVICE_TOKEN'),
  catalog: requiredEnv('CATALOG_SERVICE_TOKEN'),
  workflow: requiredEnv('WORKFLOW_SERVICE_TOKEN'),
  firefighting: requiredEnv('FIREFIGHTING_SERVICE_TOKEN'),
  reporting: requiredEnv('REPORTING_SERVICE_TOKEN'),
  billing: requiredEnv('BILLING_SERVICE_TOKEN'),
  audit: requiredEnv('AUDIT_SERVICE_TOKEN'),
  notification: requiredEnv('NOTIFICATION_SERVICE_TOKEN'),
};

const tokenHeaders = {
  identity: 'X-Identity-Service-Token',
  tenant: 'X-Tenant-School-Token',
  student: 'X-Student-Service-Token',
  attendance: 'X-Attendance-Service-Token',
  fee: 'X-Fee-Service-Token',
  catalog: 'X-Catalog-Service-Token',
  workflow: 'X-Workflow-Service-Token',
  firefighting: 'X-Firefighting-Service-Token',
  reporting: 'X-Reporting-Service-Token',
  billing: 'X-Billing-Service-Token',
  audit: 'X-Audit-Service-Token',
  notification: 'X-Notification-Service-Token',
};

const routes = [
  route('student', /^\/api\/v1\/classes\/[^/]+\/sections\/[^/]+\/students$/),
  route('identity', '/api/v1/auth/'),
  route('identity', '/api/v1/rbac/'),
  route('identity', '/api/v1/users'),
  route('identity', /^\/api\/v1\/schools\/[^/]+\/(admin|operations-user)$/),
  route('identity', /^\/api\/v1\/zones\/[^/]+\/admin$/),
  route('tenant', '/api/v1/classes/'),
  route('tenant', '/api/v1/classes'),
  route('tenant', '/api/v1/schools/'),
  route('tenant', '/api/v1/schools'),
  route('tenant', '/api/v1/zones/'),
  route('tenant', '/api/v1/zones'),
  route('student', '/api/v1/students/'),
  route('student', '/api/v1/students'),
  route('student', '/api/v1/workspace/students'),
  route('student', '/api/v1/student-review-items/'),
  route('firefighting', '/api/v1/workspace/firefighting'),
  route('tenant', '/api/v1/workspace/staff'),
  route('tenant', '/api/v1/workspace/timetable'),
  route('fee', '/api/v1/workspace/fees/'),
  route('reporting', '/api/v1/workspace/'),
  route('reporting', '/api/v1/workspace'),
  route('attendance', '/api/v1/attendance/'),
  route('fee', '/api/v1/fee-structure'),
  route('fee', '/api/v1/fee-assignments'),
  route('fee', '/api/v1/payments'),
  route('fee', '/api/v1/fees/'),
  route('fee', '/api/v1/receipts/'),
  route('catalog', '/api/v1/supply/'),
  route('billing', '/api/v1/sa/invoices/'),
  route('billing', '/api/v1/sa/invoices'),
  route('billing', '/api/v1/customers/'),
  route('billing', '/api/v1/customers'),
  route('billing', '/api/v1/invoices/'),
  route('billing', '/api/v1/invoices'),
  route('billing', '/api/v1/billing-payments/'),
  route('billing', '/api/v1/billing-payments'),
  route('catalog', '/api/v1/sa/orders'),
  route('tenant', '/api/v1/sa/schools'),
  route('workflow', '/api/v1/workflows/'),
  route('firefighting', '/api/v1/ff/'),
  route('audit', '/api/v1/audit-logs'),
  route('notification', '/api/v1/notifications/'),
  route('reporting', '/api/v1/approvals/'),
  route('reporting', '/api/v1/approvals'),
  route('catalog', /^\/api\/v1\/dashboard\/vendor-dues\/catalog-orders\/[^/]+\/mark-paid$/),
  route('firefighting', /^\/api\/v1\/dashboard\/vendor-dues\/firefighting\/[^/]+\/mark-paid$/),
  route('fee', '/api/v1/dashboard/finance/fee-defaulters/reminders'),
  route('reporting', '/api/v1/dashboard/'),
  route('reporting', '/api/v1/dashboard'),
  route('reporting', '/api/v1/command-centre/'),
  diagnostic('notification', '/notification-api/v1/'),
  diagnostic('audit', '/audit-api/v1/'),
  diagnostic('identity', '/identity-api/v1/'),
  diagnostic('tenant', '/tenant-api/v1/'),
  diagnostic('student', '/student-api/v1/'),
  diagnostic('attendance', '/attendance-api/v1/'),
  diagnostic('fee', '/fee-api/v1/'),
  diagnostic('catalog', '/catalog-api/v1/'),
  diagnostic('workflow', '/workflow-api/v1/'),
  diagnostic('firefighting', '/firefighting-api/v1/'),
  diagnostic('reporting', '/reporting-api/v1/'),
  diagnostic('billing', '/billing-api/v1/'),
];

const idTokenCache = new Map();

const server = http.createServer(async (req, res) => {
  try {
    const requestId = req.headers['x-request-id'] || randomUUID();
    const parsed = new URL(req.url, 'http://gateway.local');

    setSecurityHeaders(res);

    if (parsed.pathname === '/gateway-health') {
      sendJson(res, 200, { status: 'UP', service: 'custoking-api-gateway' });
      return;
    }

    // CORS: reflect only allow-listed origins; answer (and gate) preflight here.
    const corsDecision = applyCors(req, res);
    if (req.method === 'OPTIONS' && req.headers['access-control-request-method']) {
      if (corsDecision === 'allowed') {
        res.setHeader('Access-Control-Allow-Methods', CORS_ALLOW_METHODS);
        res.setHeader('Access-Control-Allow-Headers', CORS_ALLOW_HEADERS);
        res.setHeader('Access-Control-Max-Age', CORS_MAX_AGE);
        res.statusCode = 204;
        res.end();
        return;
      }
      sendJson(res, 403, { message: 'Origin not allowed' });
      return;
    }

    // Global rate limit (token-bucket) before any upstream work.
    const limit = checkRateLimit(req);
    if (!limit.allowed) {
      res.setHeader('Retry-After', String(limit.retryAfter));
      sendJson(res, 429, { message: 'Too many requests' });
      return;
    }

    // Reject oversized bodies before streaming them to an upstream.
    if (bodyTooLarge(req)) {
      sendJson(res, 413, { message: 'Payload too large' });
      return;
    }

    const matched = routes.find((candidate) => candidate.matches(parsed.pathname));
    if (matched) {
      if (requiresUserAuth(parsed.pathname) && AUTH_MODE !== 'permissive') {
        const principal = await authenticate(req, requestId);
        if (!principal) {
          sendJson(res, 401, { message: 'Unauthorized' });
          return;
        }
        await proxy(req, res, matched, parsed, requestId, principal);
        return;
      }
      await proxy(req, res, matched, parsed, requestId, null);
      return;
    }

    if (parsed.pathname.startsWith('/api/v1/')) {
      sendJson(res, 404, { message: 'No service route is configured for this API path' });
      return;
    }

    await proxyFrontend(req, res, parsed, requestId);
  } catch (error) {
    console.error('gateway.error', error);
    if (!res.headersSent) {
      sendJson(res, 502, { message: 'Gateway upstream error' });
    } else {
      res.end();
    }
  }
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`custoking-api-gateway listening on ${PORT}, auth=${AUTH_MODE}, cloudRunAuth=${CLOUD_RUN_AUTH}`);
  });
}

function route(service, matcher) {
  return {
    service,
    rewritePrefix: null,
    matches(pathname) {
      if (matcher instanceof RegExp) return matcher.test(pathname);
      return pathname === matcher
        || pathname.startsWith(matcher.endsWith('/') ? matcher : `${matcher}/`);
    },
    rewrite(pathname) {
      return pathname;
    },
  };
}

function diagnostic(service, prefix) {
  return {
    service,
    rewritePrefix: prefix,
    matches(pathname) {
      return pathname.startsWith(prefix);
    },
    rewrite(pathname) {
      return `/api/v1/${pathname.slice(prefix.length)}`;
    },
  };
}

function requiresUserAuth(pathname) {
  if (!pathname.startsWith('/api/v1/') && !/^\/[a-z-]+-api\/v1\//.test(pathname)) {
    return false;
  }
  return ![
    '/api/v1/auth/login',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
  ].some((publicPath) => pathname === publicPath || pathname.startsWith(`${publicPath}/`));
}

// Verify an HS512 JWT locally with the shared secret — no network call.
// Returns the decoded claims, or null on any failure. `nowSeconds` is injectable for tests.
function verifyJwtLocally(token, secret, nowSeconds) {
  if (typeof token !== 'string' || typeof secret !== 'string' || !secret) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [headerB64, payloadB64, sigB64] = parts;
  let header;
  let payload;
  try {
    header = JSON.parse(Buffer.from(headerB64, 'base64url').toString('utf8'));
    payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  // Enforce HS512 only — rejects "none" and algorithm-confusion attacks.
  if (!header || header.alg !== 'HS512') return null;
  const expected = crypto.createHmac('sha512', secret).update(`${headerB64}.${payloadB64}`).digest('base64url');
  const provided = Buffer.from(sigB64);
  const expectedBuf = Buffer.from(expected);
  if (provided.length !== expectedBuf.length) return null;
  if (!crypto.timingSafeEqual(provided, expectedBuf)) return null;
  if (typeof payload.exp === 'number' && nowSeconds >= payload.exp) return null;
  if (typeof payload.nbf === 'number' && nowSeconds < payload.nbf) return null;
  return payload;
}

// Map verified JWT claims to the same principal shape introspect() returns.
// Returns null for un-enriched tokens (ver < 2) so the caller falls back to introspection.
function principalFromClaims(claims) {
  if (!claims || typeof claims.ver !== 'number' || claims.ver < 2) return null;
  return {
    userId: claims.uid ?? null,
    email: claims.sub ?? null,
    role: claims.role ?? null,
    branchId: claims.sid ?? null,
    zoneId: claims.zid ?? null,
  };
}

// Resolve the caller's principal. Prefers local HS512 verification; falls back to
// introspection for un-enriched legacy tokens or when local verify is off/misconfigured.
// `opts` overrides are for deterministic unit tests.
async function authenticate(req, requestId, opts = {}) {
  const localVerify = opts.localVerify !== undefined ? opts.localVerify : LOCAL_JWT_VERIFY;
  const secret = opts.secret !== undefined ? opts.secret : APP_JWT_SECRET;
  const introspectFn = opts.introspect || introspect;
  const now = opts.now !== undefined ? opts.now : Math.floor(Date.now() / 1000);

  const auth = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(auth);
  if (!match) return null;

  if (localVerify && secret) {
    const claims = verifyJwtLocally(match[1], secret, now);
    if (!claims) return null; // bad signature / expired / wrong alg → 401, no fallback
    const principal = principalFromClaims(claims);
    if (principal) return principal; // enriched token → no network call
    return introspectFn(req, requestId); // valid but un-enriched → fall back
  }
  if (localVerify && !secret && !warnedMissingJwtSecret) {
    warnedMissingJwtSecret = true;
    console.warn('gateway.localjwt: GATEWAY_LOCAL_JWT_VERIFY enabled but APP_JWT_SECRET unset; using introspection');
  }
  return introspectFn(req, requestId);
}

async function introspect(req, requestId) {
  const auth = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(auth);
  if (!match) return null;

  const target = new URL('/api/v1/auth/introspect', upstreams.identity);
  const headers = {
    'content-type': 'application/json',
    'x-request-id': requestId,
    [tokenHeaders.identity]: serviceTokens.identity,
  };
  await addCloudRunAuthorization(headers, upstreams.identity);

  const response = await fetch(target, {
    method: 'POST',
    headers,
    body: JSON.stringify({ token: match[1] }),
  });
  if (!response.ok) return null;
  const payload = await response.json();
  return payload && payload.active ? payload.principal : null;
}

async function proxyFrontend(req, res, parsed, requestId) {
  await proxyToUrl(req, res, new URL(`${parsed.pathname}${parsed.search}`, upstreams.frontend), requestId, null, null);
}

async function proxy(req, res, matched, parsed, requestId, principal) {
  const upstream = upstreams[matched.service];
  const targetPath = `${matched.rewrite(parsed.pathname)}${parsed.search}`;
  const target = new URL(targetPath, upstream);
  await proxyToUrl(req, res, target, requestId, matched.service, principal);
}

async function proxyToUrl(req, res, target, requestId, service, principal) {
  const headers = outboundHeaders(req, requestId);
  if (service) {
    headers[tokenHeaders[service]] = serviceTokens[service];
  }
  if (principal) {
    headers['x-authenticated-user-id'] = stringOrEmpty(principal.userId);
    headers['x-authenticated-email'] = stringOrEmpty(principal.email);
    headers['x-authenticated-role'] = stringOrEmpty(principal.role);
    headers['x-authenticated-school-id'] = stringOrEmpty(principal.branchId);
    headers['x-authenticated-zone-id'] = stringOrEmpty(principal.zoneId);
  }
  await addCloudRunAuthorization(headers, target);

  const init = {
    method: req.method,
    headers,
    redirect: 'manual',
  };
  if (!['GET', 'HEAD'].includes(req.method)) {
    init.body = req;
    init.duplex = 'half';
  }

  const response = await fetch(target, init);
  res.statusCode = response.status;
  response.headers.forEach((value, key) => {
    if (!isResponseHopHeader(key)) {
      res.setHeader(key, value);
    }
  });
  if (typeof response.headers.getSetCookie === 'function') {
    const cookies = response.headers.getSetCookie();
    if (cookies.length) res.setHeader('set-cookie', cookies);
  }
  if (response.body) {
    for await (const chunk of response.body) {
      res.write(chunk);
    }
  }
  res.end();
}

function outboundHeaders(req, requestId) {
  const headers = {};
  for (const [key, value] of Object.entries(req.headers)) {
    if (!isRequestHopHeader(key) && !isClientSpoofableHeader(key)) {
      headers[key] = Array.isArray(value) ? value.join(', ') : value;
    }
  }
  headers['x-request-id'] = requestId;
  headers['x-forwarded-for'] = appendForwardedFor(req);
  headers['x-forwarded-proto'] = req.headers['x-forwarded-proto'] || 'https';
  return headers;
}

// Headers only the gateway may set — a client-supplied value is dropped so it can
// never be trusted by an upstream. The gateway re-injects the authenticated identity
// (from the verified JWT principal) and the correct per-service token after this.
function isClientSpoofableHeader(name) {
  const n = name.toLowerCase();
  return n.startsWith('x-authenticated-') || n.endsWith('-service-token');
}

async function addCloudRunAuthorization(headers, target) {
  const url = target instanceof URL ? target : new URL(target);
  const shouldAdd = CLOUD_RUN_AUTH === 'always' || (CLOUD_RUN_AUTH === 'auto' && url.hostname.endsWith('.run.app'));
  if (!shouldAdd) return;
  headers.authorization = `Bearer ${await cloudRunIdToken(url.origin)}`;
}

async function cloudRunIdToken(audience) {
  const cached = idTokenCache.get(audience);
  const now = Date.now();
  if (cached && cached.expiresAt > now + 60_000) {
    return cached.token;
  }
  const metadataUrl = `http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=${encodeURIComponent(audience)}&format=full`;
  const response = await fetch(metadataUrl, { headers: { 'Metadata-Flavor': 'Google' } });
  if (!response.ok) {
    throw new Error(`metadata identity token request failed: ${response.status}`);
  }
  const token = await response.text();
  idTokenCache.set(audience, { token, expiresAt: now + 45 * 60_000 });
  return token;
}

function appendForwardedFor(req) {
  const current = req.headers['x-forwarded-for'];
  const remote = req.socket.remoteAddress;
  return current ? `${current}, ${remote}` : remote;
}

function isRequestHopHeader(name) {
  return [
    'connection',
    'content-length',
    'expect',
    'host',
    'keep-alive',
    'proxy-authenticate',
    'proxy-authorization',
    'te',
    'trailer',
    'transfer-encoding',
    'upgrade',
  ].includes(name.toLowerCase());
}

function isResponseHopHeader(name) {
  return [
    'connection',
    'content-encoding',
    'content-length',
    'keep-alive',
    'proxy-authenticate',
    'proxy-authorization',
    'te',
    'trailer',
    'transfer-encoding',
    'upgrade',
  ].includes(name.toLowerCase());
}

function setSecurityHeaders(res) {
  res.setHeader('Strict-Transport-Security', HSTS);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', REFERRER_POLICY);
  res.setHeader('Content-Security-Policy', CSP);
}

function isOriginAllowed(origin) {
  return !!origin && CORS_ALLOWED_ORIGINS.includes(origin);
}

// Sets CORS response headers for allow-listed origins only.
// Returns 'allowed' (origin on the list), 'blocked' (origin present but not listed), or 'none' (no Origin header).
function applyCors(req, res) {
  const origin = req.headers.origin;
  if (!origin) return 'none';
  if (isOriginAllowed(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.setHeader('Vary', 'Origin');
    return 'allowed';
  }
  return 'blocked';
}

function clientIp(req) {
  const forwarded = req.headers['x-forwarded-for'];
  if (forwarded) {
    return String(forwarded).split(',')[0].trim();
  }
  return (req.socket && req.socket.remoteAddress) || 'unknown';
}

function rateLimitKey(req) {
  const auth = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(auth);
  if (match) return `tok:${match[1]}`;
  return `ip:${clientIp(req)}`;
}

// Token-bucket limiter. `opts` (rps/burst/buckets/now) is for deterministic unit tests.
function checkRateLimit(req, opts = {}) {
  const rps = opts.rps !== undefined ? opts.rps : RATE_LIMIT_RPS;
  const burst = opts.burst !== undefined ? opts.burst : RATE_LIMIT_BURST;
  const buckets = opts.buckets || rateBuckets;
  const now = opts.now !== undefined ? opts.now : Date.now();
  if (!rps || rps <= 0) return { allowed: true };

  const key = rateLimitKey(req);
  let bucket = buckets.get(key);
  if (!bucket) {
    if (buckets.size >= RATE_LIMIT_MAX_KEYS) pruneRateBuckets(buckets, now);
    bucket = { tokens: burst, last: now };
    buckets.set(key, bucket);
  }
  const elapsedSeconds = Math.max(0, (now - bucket.last) / 1000);
  bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * rps);
  bucket.last = now;
  if (bucket.tokens >= 1) {
    bucket.tokens -= 1;
    return { allowed: true };
  }
  return { allowed: false, retryAfter: Math.max(1, Math.ceil((1 - bucket.tokens) / rps)) };
}

// Drop buckets idle for >60s to bound memory under key churn (e.g. per-token keys).
function pruneRateBuckets(buckets, now) {
  for (const [key, bucket] of buckets) {
    if (now - bucket.last > 60_000) buckets.delete(key);
  }
}

function bodyTooLarge(req) {
  if (!MAX_BODY_BYTES || MAX_BODY_BYTES <= 0) return false;
  const length = Number(req.headers['content-length']);
  return Number.isFinite(length) && length > MAX_BODY_BYTES;
}

function sendJson(res, status, payload) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify(payload));
}

function envUrl(name, fallback) {
  return new URL(process.env[name] || fallback);
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value || value === 'unused') {
    throw new Error(`Missing required gateway service token: ${name}`);
  }
  return value;
}

function stringOrEmpty(value) {
  return value === null || value === undefined ? '' : String(value);
}

module.exports = {
  server,
  routes,
  route,
  diagnostic,
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
  authenticate,
};
