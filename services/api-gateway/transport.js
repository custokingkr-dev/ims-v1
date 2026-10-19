'use strict';

const { once } = require('node:events');
const { context: otelContext } = require('@opentelemetry/api');
const { applyDeprecationHeaders } = require('./api-contract');

function boundedMilliseconds(value, fallback) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 100 && number <= 600_000 ? number : fallback;
}

function stringOrEmpty(value) {
  return value === null || value === undefined ? '' : String(value);
}

class PayloadTooLargeError extends Error {
  constructor() {
    super('Request body exceeds the configured gateway limit');
    this.name = 'PayloadTooLargeError';
    this.code = 'GATEWAY_PAYLOAD_TOO_LARGE';
  }
}

// Transport owns streaming, deadlines and service credentials, with explicit policy inputs.
function createProxyTransport({ upstreams, tokenHeaders, serviceTokens, CLOUD_RUN_AUTH, MAX_BODY_BYTES, PROXY_TIMEOUT_MS, EXPORT_TIMEOUT_MS, AUTH_TIMEOUT_MS, activeSpanContext, formatTraceparent, sendJson, fetchFn = (...args) => fetch(...args) }) {
  const idTokenCache = new Map();

  async function proxyFrontend(req, res, parsed, requestId) {
    const target = buildUpstreamTarget(upstreams.frontend, parsed.pathname, parsed.search);
    await proxyToUrl(req, res, target, requestId, null, null);
  }

  async function proxy(req, res, matched, parsed, requestId, principal, compatibility = null) {
    const upstream = upstreams[matched.service];
    const target = buildUpstreamTarget(upstream, matched.rewrite(parsed.pathname), parsed.search);
    const report = parsed.pathname === '/api/v1/notifications/provider-reports/msg91/email';
    if (report && Number(req.headers['content-length']) > 32768) {
      return sendJson(res, 413, { error: 'Provider report is too large' });
    }
    await proxyToUrl(req, res, target, requestId, matched.service, principal, {
      compatibility,
      ...(report ? { maxBodyBytes: 32768 } : {}),
    });
  }

  async function proxyToUrl(req, res, target, requestId, service, principal, opts = {}) {
    const lifecycle = proxyLifecycle(req, res, opts.timeoutMs ?? proxyDeadline(req.url));
    try {
    const headers = outboundHeaders(req, requestId);
    if (service) {
      headers[tokenHeaders[service]] = serviceTokens[service];
    }
    if (principal) {
      headers['x-authenticated-user-id'] = stringOrEmpty(principal.userId);
      headers['x-authenticated-session-id'] = stringOrEmpty(principal.sessionId);
      headers['x-authenticated-step-up-expires-at'] = stringOrEmpty(principal.stepUpExpiresAt);
      headers['x-authenticated-email'] = stringOrEmpty(principal.email);
      headers['x-authenticated-role'] = stringOrEmpty(principal.role);
      headers['x-authenticated-school-id'] = stringOrEmpty(principal.branchId);
      headers['x-authenticated-zone-id'] = stringOrEmpty(principal.zoneId);
      headers['x-authenticated-permissions'] =
        Array.isArray(principal.permissions) ? principal.permissions.join(',') : '';
      headers['x-authenticated-operator-schools'] =
        Array.isArray(principal.operatorSchools) ? principal.operatorSchools.join(',') : '';
      headers['x-authenticated-operator-scopes'] =
        Array.isArray(principal.operatorScopes) ? principal.operatorScopes.join(',') : '';
    }
    await addCloudRunAuthorization(headers, target, Boolean(principal));

    const init = {
      method: req.method,
      headers,
      redirect: 'manual',
      signal: req.gatewayAbortSignal ? AbortSignal.any([req.gatewayAbortSignal, lifecycle.signal]) : lifecycle.signal,
    };
    if (!['GET', 'HEAD'].includes(req.method)) {
      const maxBodyBytes = opts.maxBodyBytes !== undefined ? opts.maxBodyBytes : MAX_BODY_BYTES;
      init.body = boundedRequestBody(req, maxBodyBytes);
      init.duplex = 'half';
    }

    const response = await fetchFn(target, init);
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
    applyDeprecationHeaders(res, opts.compatibility);
    if (response.body) {
      for await (const chunk of response.body) {
        if (res.write(chunk) === false) await once(res, 'drain', { signal: init.signal });
      }
    }
    res.end();
    } finally {
      lifecycle.abort();
      lifecycle.dispose();
    }
  }

  function proxyDeadline(pathname = '') {
    if (/\/(?:export|download|content)(?:[/?]|$)/i.test(pathname)) return EXPORT_TIMEOUT_MS;
    if (pathname.startsWith('/api/v1/auth/')) return AUTH_TIMEOUT_MS;
    return PROXY_TIMEOUT_MS;
  }

  function proxyLifecycle(req, res, timeoutMs) {
    const controller = new AbortController();
    const abort = () => controller.abort(new Error('Proxy client disconnected'));
    const close = () => { if (!res.writableFinished) abort(); };
    const timer = setTimeout(() => {
      const error = new Error('Proxy operation exceeded its deadline');
      error.code = 'GATEWAY_UPSTREAM_TIMEOUT';
      controller.abort(error);
    }, boundedMilliseconds(timeoutMs, PROXY_TIMEOUT_MS));
    timer.unref();
    req.on?.('aborted', abort);
    res.on?.('close', close);
    if (req.aborted || res.destroyed) abort();
    return {
      signal: controller.signal,
      abort,
      dispose() { clearTimeout(timer); req.off?.('aborted', abort); res.off?.('close', close); },
    };
  }

  function outboundHeaders(req, requestId, activeContext = otelContext.active()) {
    const headers = {};
    for (const [key, value] of Object.entries(req.headers)) {
      if (!isRequestHopHeader(key) && !isClientSpoofableHeader(key)) {
        headers[key] = Array.isArray(value) ? value.join(', ') : value;
      }
    }
    headers['x-request-id'] = requestId;
    headers['x-forwarded-for'] = appendForwardedFor(req);
    headers['x-forwarded-proto'] = req.headers['x-forwarded-proto'] || 'https';
    const spanContext = activeSpanContext(activeContext);
    if (spanContext) {
      headers.traceparent = formatTraceparent(spanContext);
    }
    return headers;
  }

  function isClientSpoofableHeader(name) {
    const n = name.toLowerCase();
    return n.startsWith('x-authenticated-') || n.endsWith('-service-token') || n === 'x-ims-principal-carrier-token';
  }

  async function addCloudRunAuthorization(headers, target, principalCarrier = false) {
    const url = target instanceof URL ? target : new URL(target);
    const shouldAdd = CLOUD_RUN_AUTH === 'always' || (CLOUD_RUN_AUTH === 'auto' && url.hostname.endsWith('.run.app'));
    if (!shouldAdd) return;
    headers.authorization = `Bearer ${await cloudRunIdToken(url.origin)}`;
    if (principalCarrier) headers['x-ims-principal-carrier-token'] = headers.authorization;
  }

  async function cloudRunIdToken(audience) {
    const cached = idTokenCache.get(audience);
    const now = Date.now();
    if (cached && cached.expiresAt > now + 60_000) {
      return cached.token;
    }
    const metadataUrl = `http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=${encodeURIComponent(audience)}&format=full`;
    const response = await fetchFn(metadataUrl, { headers: { 'Metadata-Flavor': 'Google' }, signal: AbortSignal.timeout(5_000) });
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

  function bodyTooLarge(req) {
    if (!MAX_BODY_BYTES || MAX_BODY_BYTES <= 0) return false;
    const length = Number(req.headers['content-length']);
    return Number.isFinite(length) && length > MAX_BODY_BYTES;
  }

  async function* boundedRequestBody(req, maxBytes = MAX_BODY_BYTES) {
    let received = 0;
    for await (const chunk of req) {
      if (maxBytes && maxBytes > 0) {
        received += Buffer.isBuffer(chunk) ? chunk.length : Buffer.byteLength(chunk);
        if (received > maxBytes) throw new PayloadTooLargeError();
      }
      yield chunk;
    }
  }

  function isPayloadTooLargeError(error) {
    let current = error;
    while (current) {
      if (current.code === 'GATEWAY_PAYLOAD_TOO_LARGE') return true;
      current = current.cause;
    }
    return false;
  }

  function buildUpstreamTarget(upstream, pathname, search = '') {
    if (!(upstream instanceof URL)) throw new TypeError('Configured upstream must be a URL');
    if (typeof pathname !== 'string' || !pathname.startsWith('/')) {
      throw new TypeError('Proxy path must be absolute');
    }

    const encodedSegments = pathname.split('/').map((segment) => {
      let decoded;
      try {
        decoded = decodeURIComponent(segment);
      } catch {
        throw new TypeError('Proxy path contains invalid percent encoding');
      }
      if (decoded === '.' || decoded === '..' || decoded.includes('/') || decoded.includes('\\')) {
        throw new TypeError('Proxy path contains a forbidden segment');
      }
      return encodeURIComponent(decoded);
    });

    const encodedQuery = [];
    for (const [key, value] of new URLSearchParams(search)) {
      encodedQuery.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
    }

    const target = new URL(upstream.origin);
    target.pathname = encodedSegments.join('/');
    target.search = encodedQuery.length ? `?${encodedQuery.join('&')}` : '';
    if (target.origin !== upstream.origin) throw new TypeError('Proxy target escaped its configured upstream');
    return target;
  }

  return { proxyFrontend, proxy, proxyToUrl, proxyDeadline, proxyLifecycle, outboundHeaders, isClientSpoofableHeader, addCloudRunAuthorization, isRequestHopHeader, isResponseHopHeader, bodyTooLarge, boundedRequestBody, isPayloadTooLargeError, buildUpstreamTarget };
}

module.exports = { createProxyTransport, boundedMilliseconds, stringOrEmpty };
