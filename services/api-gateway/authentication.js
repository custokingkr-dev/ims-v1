'use strict';

const crypto = require('node:crypto');
const HMAC_JWT_ALGORITHMS = { HS256: 'sha256', HS384: 'sha384', HS512: 'sha512' };

// Identity remains authoritative on every request; local verification only adds rejection.
function createAuthentication({ LOCAL_JWT_VERIFY, APP_JWT_SECRET, upstreams, tokenHeaders, serviceTokens, parseBearerToken, addCloudRunAuthorization, fetchFn = (...args) => fetch(...args) }) {
  let warnedMissingJwtSecret = false;

  function requiresUserAuth(pathname) {
    // Exact provider callback only. Platform verifies both the injected service identity
    // and its dedicated callback secret; sibling paths and compatibility aliases stay private.
    if (pathname === '/api/v1/notifications/provider-reports/msg91/email') return false;
    if (!pathname.startsWith('/api/v1/') && !/^\/[a-z-]+-api\/v1\//.test(pathname)) {
      return false;
    }
    return ![
      '/api/v1/auth/login',
      '/api/v1/auth/refresh',
      '/api/v1/auth/logout',
      '/api/v1/auth/password-reset/capabilities',
      '/api/v1/auth/password-reset/request',
      '/api/v1/auth/password-reset/confirm',
    ].some((publicPath) => pathname === publicPath || pathname.startsWith(`${publicPath}/`));
  }

  function isCookieAuthPath(pathname) {
    return [
      '/api/v1/auth/refresh',
      '/api/v1/auth/logout',
    ].some((cookiePath) => pathname === cookiePath || pathname.startsWith(`${cookiePath}/`));
  }

  function verifyJwtLocally(token, secret, nowSeconds, detail = {}) {
    const fail = (reason) => { detail.reason = reason; return null; };
    if (typeof token !== 'string' || typeof secret !== 'string' || !secret) return fail('token_invalid');
    const parts = token.split('.');
    if (parts.length !== 3) return fail('token_malformed');
    const [headerB64, payloadB64, sigB64] = parts;
    let header;
    let payload;
    try {
      header = JSON.parse(Buffer.from(headerB64, 'base64url').toString('utf8'));
      payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
    } catch {
      return fail('token_malformed');
    }
    // Identity emits HS256; pin that algorithm before interpreting any claims.
    const digest = header?.alg === 'HS256' ? HMAC_JWT_ALGORITHMS.HS256 : null;
    if (!digest) return fail('token_algorithm_rejected');
    const expected = crypto.createHmac(digest, secret).update(`${headerB64}.${payloadB64}`).digest('base64url');
    // base64url signatures are ASCII, so comparing the encoded text byte-for-byte is equivalent to comparing raw bytes.
    const provided = Buffer.from(sigB64);
    const expectedBuf = Buffer.from(expected);
    if (provided.length !== expectedBuf.length) return fail('token_invalid');
    if (!crypto.timingSafeEqual(provided, expectedBuf)) return fail('token_invalid');
    if (typeof payload.exp === 'number' && nowSeconds >= payload.exp) return fail('token_expired');
    if (typeof payload.nbf === 'number' && nowSeconds < payload.nbf) return fail('token_not_yet_valid');
    if (payload.type === 'refresh') return fail('refresh_token_as_bearer');
    if (!Number.isFinite(payload.exp) || payload.type !== 'access'
        || payload.iss !== (process.env.APP_JWT_ISSUER || 'ims-identity')
        || payload.aud !== (process.env.APP_JWT_AUDIENCE || 'ims-api')) return fail('token_binding_rejected');
    return payload;
  }

  function principalFromClaims(claims) {
    if (!claims || typeof claims.ver !== 'number' || claims.ver < 2) return null;
    return {
      userId: claims.uid ?? null,
      email: claims.sub ?? null,
      role: claims.role ?? null,
      branchId: claims.sid ?? null,
      zoneId: claims.zid ?? null,
      permissions: Array.isArray(claims.perms) ? claims.perms : [],
      operatorSchools: Array.isArray(claims.ops_schools) ? claims.ops_schools : [],
      // Item-specific operator scopes as 'schoolId:CATEGORY'; operatorSchools stays school-wide only.
      operatorScopes: Array.isArray(claims.ops_scopes) ? claims.ops_scopes : [],
    };
  }

  async function authenticate(req, requestId, opts = {}) {
    const localVerify = opts.localVerify !== undefined ? opts.localVerify : LOCAL_JWT_VERIFY;
    const secret = opts.secret !== undefined ? opts.secret : APP_JWT_SECRET;
    const introspectFn = opts.introspect !== undefined ? opts.introspect : introspect;
    const now = opts.now !== undefined ? opts.now : Math.floor(Date.now() / 1000);

    // Every rejection path below records why, so a 401 in the request log is diagnosable.
    const reject = (reason) => { req.authFailureReason = reason; return null; };

    const token = parseBearerToken(req.headers.authorization);
    if (!token) return reject('missing_bearer');

    let localFailure = null;
    if (localVerify && secret) {
      const detail = {};
      const previous = opts.previousSecret ?? process.env.APP_JWT_PREVIOUS_SECRET;
      const currentClaims = verifyJwtLocally(token, secret, now, detail);
      const previousClaims = previous ? verifyJwtLocally(token, previous, now, detail) : null;
      const claims = currentClaims || previousClaims;
      if (!claims) localFailure = detail.reason || 'token_invalid';
    }
    if (localVerify && !secret && !warnedMissingJwtSecret) {
      warnedMissingJwtSecret = true;
      console.warn('gateway.localjwt: GATEWAY_LOCAL_JWT_VERIFY enabled but APP_JWT_SECRET unset; using introspection');
    }
    // Authentication authority is unconditional: no client claim can skip server-side validation.
    // Optional local verification can only add a rejection after identity has evaluated the token.
    const introspected = await introspectFn(req, requestId);
    if (localFailure) return reject(localFailure);
    return introspected || reject('introspection_rejected');
  }

  async function introspect(req, requestId) {
    const token = parseBearerToken(req.headers.authorization);
    if (!token) return null;

    const target = new URL('/api/v1/auth/introspect', upstreams.identity);
    const headers = {
      'content-type': 'application/json',
      'x-request-id': requestId,
      [tokenHeaders.identity]: serviceTokens.identity,
    };
    await addCloudRunAuthorization(headers, upstreams.identity);

    const requestUrl = new URL(req.url || '/', 'http://gateway.local');
    const requestPath = decodeURIComponent(requestUrl.pathname);
    const schoolCandidate = /\/schools\/(\d+)(?:\/|$)/.exec(requestPath)?.[1] || requestUrl.searchParams.get('schoolId');
    const schoolId = schoolCandidate && /^\d+$/.test(schoolCandidate) && Number.isSafeInteger(Number(schoolCandidate))
      && Number(schoolCandidate) > 0 ? Number(schoolCandidate) : undefined;

    const response = await fetchFn(target, {
      method: 'POST',
      headers,
      body: JSON.stringify({ token, method: req.method || 'GET', path: requestPath, ...(schoolId ? { schoolId } : {}) }),
      signal: req.gatewayAbortSignal
        ? AbortSignal.any([req.gatewayAbortSignal, AbortSignal.timeout(10_000)])
        : AbortSignal.timeout(10_000),
    });
    if (response.status === 401) return null;
    if (response.status === 403) {
      const denied = await response.json().catch(() => null);
      if (denied?.code === 'STEP_UP_REQUIRED') {
        const error = new Error('Passkey verification required');
        error.code = 'STEP_UP_REQUIRED';
        throw error;
      }
      return null;
    }
    if (response.status === 429) {
      const error = new Error('Identity request quota exceeded');
      error.code = 'IDENTITY_QUOTA_EXCEEDED';
      error.retryAfter = Math.max(1, Math.min(3600, Number(response.headers.get('retry-after')) || 60));
      throw error;
    }
    if (!response.ok) throw new Error(`Identity introspection unavailable (${response.status})`);
    const payload = await response.json();
    return payload && payload.active ? payload.principal : null;
  }

  function requiresStepUp(pathname, method, principal) {
    if (/^\/api\/v1\/auth\/(?:passkeys|logout|refresh)(?:\/|$)/.test(pathname)) return false;
    const mutating = !['GET', 'HEAD', 'OPTIONS'].includes(method);
    if (principal?.role === 'SUPERADMIN' && mutating) return true;
    return /\/(?:export|exports)(?:\/|$)/i.test(pathname)
      || (method === 'DELETE' && /\/students(?:\/|$)/.test(pathname))
      || (mutating && /\/(?:payments|billing-payments|pay|refunds|invoices|purge|roles|permissions|assignments)(?:\/|$)/.test(pathname));
  }

  return { requiresUserAuth, isCookieAuthPath, verifyJwtLocally, principalFromClaims, authenticate, introspect, requiresStepUp };
}

module.exports = { createAuthentication };
