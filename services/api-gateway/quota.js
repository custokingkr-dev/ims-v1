'use strict';

const crypto = require('node:crypto');
const { isIP } = require('node:net');

// Each gateway owns its budgets; verified associations group quotas and never authenticate.
function createQuotaControls({ MAX_BEARER_TOKEN_BYTES, RATE_LIMIT_RPS, RATE_LIMIT_BURST, RATE_LIMIT_MAX_KEYS, TRUSTED_PROXY_HOPS }) {
  const rateBuckets = new Map();
  const verifiedRateAssociations = new Map();
  const VERIFIED_RATE_ASSOCIATION_TTL_MS = 60_000;
  const VERIFIED_RATE_ASSOCIATION_MAX_KEYS = 2_000;

  function clientIp(req, trustedHops = TRUSTED_PROXY_HOPS) {
    const forwarded = req.headers['x-forwarded-for'];
    if (forwarded && Number.isSafeInteger(trustedHops) && trustedHops > 0) {
      const chain = String(forwarded).split(',').map(value => value.trim());
      const selected = chain[chain.length - trustedHops];
      if (selected && isIP(selected)) return selected;
    }
    return (req.socket && req.socket.remoteAddress) || 'unknown';
  }

  function rateLimitKey(req) {
    const token = parseBearerToken(req.headers.authorization);
    if (token) {
      const digest = crypto.createHash('sha256').update(token, 'ascii').digest('base64url');
      return `tok-sha256:${digest}`;
    }
    return `ip:${clientIp(req)}`;
  }

  function verifiedActorBudgetKey(principal) {
    const raw = principal?.userId;
    const id = typeof raw === 'string' && /^[1-9][0-9]*$/.test(raw) ? Number(raw) : raw;
    return Number.isSafeInteger(id) && id > 0 ? `verified-user:${id}` : null;
  }

  function bearerBudgetFingerprint(req) {
    const token = parseBearerToken(req.headers.authorization);
    return token ? crypto.createHash('sha256').update(token, 'ascii').digest('base64url') : null;
  }

  function preAuthRateLimitKey(req, opts = {}) {
    const now = opts.now ?? Date.now();
    const associations = opts.associations || verifiedRateAssociations;
    const fingerprint = bearerBudgetFingerprint(req);
    const known = fingerprint && associations.get(fingerprint);
    if (known && known.expiresAt > now) return known.key;
    if (fingerprint) associations.delete(fingerprint);
    return `ip:${clientIp(req, opts.trustedHops ?? TRUSTED_PROXY_HOPS)}`;
  }

  function rememberVerifiedRatePrincipal(req, principal, opts = {}) {
    const key = verifiedActorBudgetKey(principal);
    const fingerprint = bearerBudgetFingerprint(req);
    if (!key || !fingerprint) return false;
    const associations = opts.associations || verifiedRateAssociations;
    const now = opts.now ?? Date.now();
    const ttlMs = Math.max(1, Math.min(Number(opts.ttlMs) || VERIFIED_RATE_ASSOCIATION_TTL_MS, VERIFIED_RATE_ASSOCIATION_TTL_MS));
    const maxKeys = Math.max(1, Math.min(Math.floor(Number(opts.maxAssociations) || VERIFIED_RATE_ASSOCIATION_MAX_KEYS), VERIFIED_RATE_ASSOCIATION_MAX_KEYS));
    for (const [digest, entry] of associations) if (entry.expiresAt <= now) associations.delete(digest);
    if (!associations.has(fingerprint) && associations.size >= maxKeys) return false;
    associations.set(fingerprint, { key, expiresAt: now + ttlMs });
    return true;
  }

  function forgetVerifiedRatePrincipal(req, opts = {}) {
    const fingerprint = bearerBudgetFingerprint(req);
    if (fingerprint) (opts.associations || verifiedRateAssociations).delete(fingerprint);
  }

  function checkVerifiedUserRateLimit(req, principal, preAuthKey, opts = {}) {
    const key = verifiedActorBudgetKey(principal);
    if (!key) return { allowed: false, retryAfter: 60 };
    rememberVerifiedRatePrincipal(req, principal, opts);
    // The known token already consumed this user's budget before introspection.
    // First use/token rotation must also consume the same user budget, after validation.
    return preAuthKey === key ? { allowed: true } : checkRateLimit(req, { ...opts, key });
  }

  function checkRateLimit(req, opts = {}) {
    const rps = opts.rps !== undefined ? opts.rps : RATE_LIMIT_RPS;
    const burst = opts.burst !== undefined ? opts.burst : RATE_LIMIT_BURST;
    const buckets = opts.buckets || rateBuckets;
    const now = opts.now !== undefined ? opts.now : Date.now();
    const configuredMaxKeys = opts.maxKeys !== undefined ? opts.maxKeys : RATE_LIMIT_MAX_KEYS;
    const maxKeys = Number.isFinite(configuredMaxKeys) && configuredMaxKeys > 0
      ? Math.floor(configuredMaxKeys)
      : 1;
    if (!rps || rps <= 0) return { allowed: true };

    // Before verification, neither a bearer value nor client-supplied leftmost XFF
    // can create a new budget. An optional verified actor budget is additive.
    const key = opts.key ?? `ip:${clientIp(req, opts.trustedHops ?? TRUSTED_PROXY_HOPS)}`;
    let bucket = buckets.get(key);
    if (!bucket) {
      if (buckets.size >= maxKeys) pruneRateBuckets(buckets, now);
      // A full map can contain active budgets: fail admission rather than letting
      // attacker churn evict a depleted budget.
      if (buckets.size >= maxKeys) return { allowed: false, retryAfter: 60 };
      bucket = { tokens: burst, last: now };
      buckets.set(key, bucket);
    }
    const elapsedSeconds = Math.max(0, (now - bucket.last) / 1000);
    bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * rps);
    bucket.last = now;
    // Keep idle-pruning order consistent with last activity.
    buckets.delete(key);
    buckets.set(key, bucket);
    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return { allowed: true };
    }
    return { allowed: false, retryAfter: Math.max(1, Math.ceil((1 - bucket.tokens) / rps)) };
  }

  function pruneRateBuckets(buckets, now) {
    for (const [key, bucket] of buckets) {
      if (now - bucket.last <= 60_000) break;
      buckets.delete(key);
    }
  }

  function evictOldestRateBucket(buckets) {
    const oldestKey = buckets.keys().next().value;
    if (oldestKey !== undefined) buckets.delete(oldestKey);
  }

  function parseBearerToken(value) {
    if (typeof value !== 'string' || value.length < 8) return null;
    if (value.slice(0, 6).toLowerCase() !== 'bearer' || value.charCodeAt(6) !== 0x20) return null;

    let tokenStart = 7;
    while (tokenStart < value.length && value.charCodeAt(tokenStart) === 0x20) tokenStart += 1;
    const token = value.slice(tokenStart);
    if (!token || token.length > MAX_BEARER_TOKEN_BYTES) return null;
    for (let index = 0; index < token.length; index += 1) {
      const code = token.charCodeAt(index);
      if (code <= 0x20 || code >= 0x7f) return null;
    }
    return token;
  }

  return { clientIp, rateLimitKey, preAuthRateLimitKey, rememberVerifiedRatePrincipal, forgetVerifiedRatePrincipal, checkVerifiedUserRateLimit, checkRateLimit, parseBearerToken };
}

module.exports = { createQuotaControls };
