'use strict';

const { flushTracing } = require('./tracing');
const { classifyCompatibilityRequest } = require('./api-contract');
const { routes, route, diagnostic, isInternalUpstreamPath } = require('./routing');
const { createQuotaControls } = require('./quota');
const { createAuthentication } = require('./authentication');
const { createProxyTransport, boundedMilliseconds, stringOrEmpty } = require('./transport');

const http = require('http');
const crypto = require('crypto');
const { context: otelContext, trace: otelTrace, TraceFlags } = require('@opentelemetry/api');
const { randomUUID } = crypto;

const PORT = Number(process.env.PORT || 8080);
const AUTH_MODE = (process.env.GATEWAY_AUTH_MODE || 'enforce').toLowerCase();
const CLOUD_RUN_AUTH = (process.env.GATEWAY_CLOUD_RUN_AUTH || 'auto').toLowerCase();
if ((process.env.K_SERVICE || process.env.NODE_ENV === 'production') && AUTH_MODE !== 'enforce') {
  throw new Error('Deployed gateways must enforce authentication');
}

// Optional signature prefilter. Current permissions and session revocation are always
// resolved through identity; embedded claims are never authoritative for access scope.
const LOCAL_JWT_VERIFY = (process.env.GATEWAY_LOCAL_JWT_VERIFY || 'disabled').toLowerCase() === 'enabled';
const APP_JWT_SECRET = process.env.APP_JWT_SECRET || '';

// --- Edge security controls (Phase 0 / SEC-P0-2) ---
// Strict CORS: an explicit origin allowlist; never `*` together with credentials.
const CORS_ALLOWED_ORIGINS = (process.env.GATEWAY_CORS_ALLOWED_ORIGINS || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const CORS_ALLOW_METHODS = process.env.GATEWAY_CORS_ALLOW_METHODS || 'GET,POST,PUT,PATCH,DELETE,OPTIONS';
const CORS_ALLOW_HEADERS = process.env.GATEWAY_CORS_ALLOW_HEADERS || 'Authorization,Content-Type,X-Request-ID,X-Student-Delete-Confirmation,Idempotency-Key';
const CORS_MAX_AGE = process.env.GATEWAY_CORS_MAX_AGE || '600';

// Security response headers (overridable so deployments can tune the CSP for the SPA).
const CSP = process.env.GATEWAY_CSP
  || "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
  + "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
  + "frame-ancestors 'none'";
const HSTS = process.env.GATEWAY_HSTS || 'max-age=63072000; includeSubDomains; preload';
const REFERRER_POLICY = process.env.GATEWAY_REFERRER_POLICY || 'strict-origin-when-cross-origin';
// Match the frontend policy, including its same-origin student photo capture.
const PERMISSIONS_POLICY = 'geolocation=(), microphone=(), camera=(self)';

// Max request body size in bytes (0 disables the check). Default 8 MiB to allow
// 5 MiB file uploads plus multipart envelope overhead.
const MAX_BODY_BYTES = Number(process.env.GATEWAY_MAX_BODY_BYTES || 8 * 1024 * 1024);

// Per-replica token-bucket rate limit, keyed by a digest of the bearer token (else client IP).
// Keep bearer credentials out of long-lived process memory and reject unusually large values
// before either authentication or rate-limit state is allocated.
const configuredMaxBearerTokenBytes = Number(process.env.GATEWAY_MAX_BEARER_TOKEN_BYTES || 8 * 1024);
const MAX_BEARER_TOKEN_BYTES = Number.isSafeInteger(configuredMaxBearerTokenBytes)
  && configuredMaxBearerTokenBytes > 0
  ? configuredMaxBearerTokenBytes
  : 8 * 1024;
const RATE_LIMIT_RPS = Number(process.env.GATEWAY_RATE_LIMIT_RPS || 50);
const RATE_LIMIT_BURST = Number(process.env.GATEWAY_RATE_LIMIT_BURST || 100);
const RATE_LIMIT_MAX_KEYS = Number(process.env.GATEWAY_RATE_LIMIT_MAX_KEYS || 50_000);
const TRUSTED_PROXY_HOPS = Math.max(0, Math.min(8, Number(process.env.GATEWAY_TRUSTED_PROXY_HOPS || 0) || 0));
const PROXY_TIMEOUT_MS = boundedMilliseconds(process.env.GATEWAY_PROXY_TIMEOUT_MS, 30_000);
const EXPORT_TIMEOUT_MS = boundedMilliseconds(process.env.GATEWAY_EXPORT_TIMEOUT_MS, 180_000);
const AUTH_TIMEOUT_MS = boundedMilliseconds(process.env.GATEWAY_AUTH_TIMEOUT_MS, 60_000);

const upstreams = {
  frontend: envUrl('FRONTEND_UPSTREAM', 'http://frontend:8080'),
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

const { clientIp, rateLimitKey, preAuthRateLimitKey, rememberVerifiedRatePrincipal, forgetVerifiedRatePrincipal, checkVerifiedUserRateLimit, checkRateLimit, parseBearerToken } = createQuotaControls({
  MAX_BEARER_TOKEN_BYTES, RATE_LIMIT_RPS, RATE_LIMIT_BURST, RATE_LIMIT_MAX_KEYS, TRUSTED_PROXY_HOPS,
});

const { proxyFrontend, proxy, proxyToUrl, proxyDeadline, proxyLifecycle, outboundHeaders, isClientSpoofableHeader, addCloudRunAuthorization, isRequestHopHeader, isResponseHopHeader, bodyTooLarge, boundedRequestBody, isPayloadTooLargeError, buildUpstreamTarget } = createProxyTransport({
  upstreams, tokenHeaders, serviceTokens, CLOUD_RUN_AUTH, MAX_BODY_BYTES,
  PROXY_TIMEOUT_MS, EXPORT_TIMEOUT_MS, AUTH_TIMEOUT_MS, activeSpanContext, formatTraceparent, sendJson,
});

const { requiresUserAuth, isCookieAuthPath, verifyJwtLocally, principalFromClaims, authenticate, introspect, requiresStepUp } = createAuthentication({
  LOCAL_JWT_VERIFY, APP_JWT_SECRET, upstreams, tokenHeaders, serviceTokens,
  parseBearerToken, addCloudRunAuthorization,
});

const server = http.createServer(async (req, res) => {
  const requestLifecycle = proxyLifecycle(req, res, proxyDeadline(req.url));
  req.gatewayAbortSignal = requestLifecycle.signal;
  let requestId = req.headers['x-request-id'] || randomUUID();
  let parsed = null;
  let matchedService = null;
  let compatibility = null;
  // Tenant context for the request log. Set only after a principal is verified, so an unauthenticated
  // 401 is never attributed to a school.
  //
  // Deliberately two scalars rather than the principal object: `principal` also carries `email`, and
  // spreading it here would put email addresses into the _Default log bucket, which is global rather
  // than India-resident. The two ids are what every per-tenant metric needs; the email is not.
  let schoolId = null;
  let userId = null;
  const startedAt = process.hrtime.bigint();
  const traceFields = currentTraceFields(req);
  const gatewaySpan = otelTrace.getTracer('api-gateway').startSpan(
    'gateway.request.boundary',
    {
      attributes: {
        'http.request.method': req.method || '',
      },
    },
    otelContext.active(),
  );
  const originalEnd = res.end.bind(res);
  let responseEnded = false;
  res.end = (...args) => {
    if (responseEnded) return res;
    responseEnded = true;
    gatewaySpan.setAttribute('http.response.status_code', res.statusCode);
    if (parsed) gatewaySpan.setAttribute('url.path', parsed.pathname);
    const sampled = (gatewaySpan.spanContext().traceFlags & TraceFlags.SAMPLED) !== 0;
    gatewaySpan.end();
    if (!sampled) return originalEnd(...args);

    // Keep the request open until this sampled boundary span is exported. Cloud Run may suspend
    // request-based CPU as soon as originalEnd() completes, which can strand a normal batch timer.
    flushTracing()
      .catch((error) => {
        logJson('ERROR', 'gateway.tracing.flush_failed', {
          requestId,
          error: error && error.message ? error.message : String(error),
        }, traceFields);
      })
      .finally(() => originalEnd(...args));
    return res;
  };
  res.on('finish', () => {
    const durationMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000;
    logJson('INFO', 'gateway.request', {
      requestId,
      method: req.method,
      path: parsed ? parsed.pathname : undefined,
      status: res.statusCode,
      durationMs: Math.round(durationMs),
      upstreamService: matchedService,
      compatibilityRouteId: compatibility ? compatibility.id : undefined,
      compatibilityRouteKind: compatibility ? compatibility.kind : undefined,
      compatibilitySuccessor: compatibility ? compatibility.successor : undefined,
      schoolId,
      userId,
      // Why a 401 happened. Undefined on every authenticated request, so it never adds noise.
      authFailureReason: req.authFailureReason,
    }, traceFields);
  });

  try {
    parsed = new URL(req.url, 'http://gateway.local');

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
    // Refresh and logout authenticate with a SameSite=None cookie. Unlike CORS response
    // filtering, rejecting a disallowed request Origin prevents a cross-site request from
    // rotating or revoking a user's refresh-token family.
    if (isCookieAuthPath(parsed.pathname) && !cookieAuthRequestAllowed(req)) {
      sendJson(res, 403, { message: 'Origin not allowed' });
      return;
    }

    // Per-replica rate limit (token-bucket) before any upstream work.
    const preAuthBudgetKey = preAuthRateLimitKey(req);
    const limit = checkRateLimit(req, { key: preAuthBudgetKey });
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

    const matched = routes.find((candidate) => candidate.matches(parsed.pathname, req.method));
    if (matched && isInternalUpstreamPath(matched.rewrite(parsed.pathname))) {
      // /api/v1/internal/** and /api/v1/pubsub/** are service-to-service surfaces guarded only by
      // Cloud Run IAM; the gateway is itself an invoker, so it must never carry a user request there.
      sendJson(res, 404, { message: 'No service route is configured for this API path' });
      return;
    }
    if (matched) {
      matchedService = matched.service;
      compatibility = classifyCompatibilityRequest(parsed.pathname, req.method);
      if (compatibility) {
        gatewaySpan.setAttribute('ims.api.compatibility_route', compatibility.id);
        gatewaySpan.setAttribute('ims.api.compatibility_kind', compatibility.kind);
      }
      if (requiresUserAuth(parsed.pathname) && AUTH_MODE !== 'permissive') {
        const principal = await authenticate(req, requestId);
        if (!principal) {
          forgetVerifiedRatePrincipal(req);
          sendJson(res, 401, { message: 'Unauthorized' });
          return;
        }
        const actorLimit = checkVerifiedUserRateLimit(req, principal, preAuthBudgetKey);
        if (!actorLimit.allowed) {
          res.setHeader('Retry-After', String(actorLimit.retryAfter));
          sendJson(res, 429, { message: 'Too many requests' });
          return;
        }
        schoolId = principal.branchId ?? null;
        userId = principal.userId ?? null;
        if (requiresStepUp(parsed.pathname, req.method, principal)
            && !(Date.parse(principal.stepUpExpiresAt) > Date.now())) {
          sendJson(res, 403, { code: 'STEP_UP_REQUIRED', message: 'Verify your passkey before this action' });
          return;
        }
        await proxy(req, res, matched, parsed, requestId, principal, compatibility);
        return;
      }
      await proxy(req, res, matched, parsed, requestId, null, compatibility);
      return;
    }

    if (parsed.pathname.startsWith('/api/v1/')) {
      sendJson(res, 404, { message: 'No service route is configured for this API path' });
      return;
    }

    matchedService = 'frontend';
    await proxyFrontend(req, res, parsed, requestId);
  } catch (error) {
    if (error?.code === 'STEP_UP_REQUIRED') {
      sendJson(res, 403, { code: 'STEP_UP_REQUIRED', message: 'Verify your passkey before this action' });
      return;
    }
    if (error && error.code === 'IDENTITY_QUOTA_EXCEEDED') {
      res.setHeader('Retry-After', String(error.retryAfter));
      sendJson(res, 429, { message: 'Too many requests. Try again later.' });
      return;
    }
    if (isPayloadTooLargeError(error)) {
      if (!res.headersSent) {
        sendJson(res, 413, { message: 'Payload too large' });
      } else {
        res.end();
      }
      return;
    }
    logJson('ERROR', 'gateway.error', {
      requestId,
      path: parsed ? parsed.pathname : undefined,
      error: error && error.message ? error.message : String(error),
      stack: error && error.stack ? error.stack : undefined,
    }, traceFields);
    if (!res.headersSent) {
      sendJson(res, error?.code === 'GATEWAY_UPSTREAM_TIMEOUT' ? 504 : 502, { message: 'Gateway upstream error', requestId });
    } else {
      res.end();
    }
  } finally {
    requestLifecycle.dispose();
  }
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`custoking-api-gateway listening on ${PORT}, auth=${AUTH_MODE}, cloudRunAuth=${CLOUD_RUN_AUTH}`);
  });
}

function setSecurityHeaders(res) {
  res.setHeader('Strict-Transport-Security', HSTS);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', REFERRER_POLICY);
  res.setHeader('Permissions-Policy', PERMISSIONS_POLICY);
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

function cookieAuthRequestAllowed(req) {
  if (req.headers.origin) return isOriginAllowed(req.headers.origin);
  // Origin-less cookie requests must establish browser same-origin provenance.
  // Nonbrowser callers send an explicit allowlisted Origin as part of the API contract.
  if (req.headers['sec-fetch-site'] !== 'same-origin') return false;
  try { return isOriginAllowed(new URL(req.headers.referer).origin); } catch { return false; }
}

function sendJson(res, status, payload) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify(payload));
}

function logJson(severity, message, fields = {}, traceFields = {}) {
  const record = {
    severity,
    message,
    ...fields,
    ...cloudLoggingTraceFields(traceFields),
  };
  for (const [key, value] of Object.entries(record)) {
    if (value === undefined || value === null || value === '') {
      delete record[key];
    }
  }
  console.log(JSON.stringify(record));
}

function currentTraceFields(req, activeContext = otelContext.active()) {
  const spanContext = activeSpanContext(activeContext) || parseTraceparentHeader(req && req.headers && req.headers.traceparent);
  if (!spanContext) return {};
  return {
    traceId: spanContext.traceId,
    spanId: spanContext.spanId,
    traceFlags: spanContext.traceFlags,
  };
}

function cloudLoggingTraceFields(fields) {
  if (!fields || !fields.traceId) return {};
  const project = process.env.GCP_PROJECT || process.env.GOOGLE_CLOUD_PROJECT || process.env.GCP_PROJECT_ID || 'custoking';
  const result = {
    traceId: fields.traceId,
    spanId: fields.spanId,
    'logging.googleapis.com/trace': `projects/${project}/traces/${fields.traceId}`,
  };
  if (fields.spanId) {
    result['logging.googleapis.com/spanId'] = fields.spanId;
  }
  return result;
}

function activeSpanContext(activeContext = otelContext.active()) {
  const span = otelTrace.getSpan(activeContext);
  if (!span || typeof span.spanContext !== 'function') return null;
  const spanContext = span.spanContext();
  return isValidTraceContext(spanContext) ? spanContext : null;
}

function parseTraceparentHeader(value) {
  const header = Array.isArray(value) ? value[0] : value;
  if (typeof header !== 'string') return null;
  const match = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.+)?$/i.exec(header.trim());
  if (!match) return null;
  const spanContext = {
    traceId: match[1].toLowerCase(),
    spanId: match[2].toLowerCase(),
    traceFlags: Number.parseInt(match[3], 16),
  };
  return isValidTraceContext(spanContext) ? spanContext : null;
}

function formatTraceparent(spanContext) {
  const flags = (spanContext.traceFlags || 0).toString(16).padStart(2, '0').slice(-2);
  return `00-${spanContext.traceId}-${spanContext.spanId}-${flags}`;
}

function isValidTraceContext(spanContext) {
  return !!spanContext
    && /^[0-9a-f]{32}$/i.test(spanContext.traceId || '')
    && /^[0-9a-f]{16}$/i.test(spanContext.spanId || '')
    && !/^0+$/.test(spanContext.traceId)
    && !/^0+$/.test(spanContext.spanId);
}

function envUrl(name, fallback) {
  const configured = new URL(process.env[name] || fallback);
  if (!['http:', 'https:'].includes(configured.protocol)
      || configured.username
      || configured.password
      || configured.pathname !== '/'
      || configured.search
      || configured.hash) {
    throw new Error(`${name} must be an HTTP(S) origin without credentials, path, query, or fragment`);
  }
  return new URL(configured.origin);
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value || value === 'unused') {
    throw new Error(`Missing required gateway service token: ${name}`);
  }
  return value;
}

module.exports = {
  server,
  routes,
  route,
  diagnostic,
  requiresUserAuth,
  isInternalUpstreamPath,
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
  preAuthRateLimitKey,
  rememberVerifiedRatePrincipal,
  forgetVerifiedRatePrincipal,
  checkVerifiedUserRateLimit,
  bodyTooLarge,
  boundedRequestBody,
  isPayloadTooLargeError,
  logJson,
  currentTraceFields,
  cloudLoggingTraceFields,
  activeSpanContext,
  parseTraceparentHeader,
  formatTraceparent,
  verifyJwtLocally,
  principalFromClaims,
  authenticate,
  introspect,
  proxyToUrl,
  proxyLifecycle,
  proxyDeadline,
  cookieAuthRequestAllowed,
  requiresStepUp,
};
