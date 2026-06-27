'use strict';

const http = require('http');
const { randomUUID } = require('crypto');

const PORT = Number(process.env.PORT || 80);
const AUTH_MODE = (process.env.GATEWAY_AUTH_MODE || 'enforce').toLowerCase();
const CLOUD_RUN_AUTH = (process.env.GATEWAY_CLOUD_RUN_AUTH || 'auto').toLowerCase();

const upstreams = {
  frontend: envUrl('FRONTEND_UPSTREAM', 'http://frontend:80'),
  identity: envUrl('IDENTITY_UPSTREAM', 'http://identity-service:8080'),
  tenant: envUrl('TENANT_SCHOOL_UPSTREAM', 'http://tenant-school-service:8080'),
  student: envUrl('STUDENT_UPSTREAM', 'http://student-service:8080'),
  attendance: envUrl('ATTENDANCE_UPSTREAM', 'http://attendance-service:8080'),
  fee: envUrl('FEE_UPSTREAM', 'http://fee-service:8080'),
  catalog: envUrl('CATALOG_UPSTREAM', 'http://catalog-service:8080'),
  workflow: envUrl('WORKFLOW_UPSTREAM', 'http://workflow-service:8080'),
  firefighting: envUrl('FIREFIGHTING_UPSTREAM', 'http://firefighting-service:8080'),
  reporting: envUrl('REPORTING_UPSTREAM', 'http://reporting-service:8080'),
  billing: envUrl('BILLING_UPSTREAM', 'http://billing-service:8080'),
  audit: envUrl('AUDIT_UPSTREAM', 'http://audit-service:8080'),
  notification: envUrl('NOTIFICATION_UPSTREAM', 'http://notification-service:8080'),
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
  route('identity', '/api/v1/users/'),
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
  route('catalog', '/api/v1/sa/orders'),
  route('tenant', '/api/v1/sa/schools'),
  route('workflow', '/api/v1/workflows/'),
  route('firefighting', '/api/v1/ff/'),
  route('audit', '/api/v1/audit-logs'),
  route('notification', '/api/v1/notifications/'),
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

    if (parsed.pathname === '/gateway-health') {
      sendJson(res, 200, { status: 'UP', service: 'custoking-api-gateway' });
      return;
    }

    const matched = routes.find((candidate) => candidate.matches(parsed.pathname));
    if (matched) {
      if (requiresUserAuth(parsed.pathname) && AUTH_MODE !== 'permissive') {
        const principal = await introspect(req, requestId);
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
    if (!isRequestHopHeader(key)) {
      headers[key] = Array.isArray(value) ? value.join(', ') : value;
    }
  }
  headers['x-request-id'] = requestId;
  headers['x-forwarded-for'] = appendForwardedFor(req);
  headers['x-forwarded-proto'] = req.headers['x-forwarded-proto'] || 'https';
  return headers;
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
  stringOrEmpty,
};
