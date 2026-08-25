// Google Sign-In with an email allowlist.
//
// WHY THIS EXISTS RATHER THAN IAP
//
// IAP was tried twice: directly on Cloud Run, then with a load balancer and IAP on the backend service.
// Both refused an authorised user who holds roles/owner AND roles/iap.admin at ORGANISATION level, with
// the accessor grant present on the correct resource in each case, no access levels configured (the
// Access Context Manager API is not even enabled), and no org policy restricting member domains. No
// request ever reached the container and IAP logged no denial.
//
// The account is a consumer Google identity and the organisation has no Cloud Identity directory. IAP
// is built around organisational identity, and that appears to be the wall. So the application does its
// own authentication, which works with ordinary gmail.com accounts and needs neither a domain nor an
// organisation.
//
// WHY THIS IS SMALL ENOUGH TO BE ACCEPTABLE
//
// Custom auth is normally a liability and this file does not pretend otherwise. What keeps it defensible
// is the shape of the problem: a read-only dashboard, an explicit allowlist, no accounts, no passwords,
// no registration, no password reset, no roles. Google verifies the human; this only decides whether a
// verified email is on a list.
//
// The one genuinely security-critical step is verifying the ID token, and it is done properly: signature
// checked against Google's published JWKS, and issuer, audience and expiry all validated. Trusting the
// token's contents without checking its signature would make the allowlist decorative, because anyone
// can write an unsigned JWT claiming any email.

import https from "node:https";
import crypto from "node:crypto";

const CLIENT_ID = process.env.OAUTH_CLIENT_ID || "";
const CLIENT_SECRET = process.env.OAUTH_CLIENT_SECRET || "";
// Comma-separated. An empty list denies everyone rather than admitting everyone -- a misconfiguration
// should lock the door, not remove it.
const ALLOWED = (process.env.DASHBOARD_ALLOWED_EMAILS || "")
  .split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
// Encrypts and authenticates browser cookies. Generated per revision when unset, which logs everyone out on deploy --
// acceptable for a dashboard, and far better than a hardcoded default that would let anyone who read
// this file mint their own session.
const COOKIE_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");
const COOKIE_KEY = crypto.createSecretKey(Buffer.from(crypto.hkdfSync(
  "sha256",
  Buffer.from(COOKIE_SECRET, "utf8"),
  Buffer.from("custoking-live-dashboard-cookie-v1", "utf8"),
  Buffer.from("authenticated-browser-cookies", "utf8"),
  32,
)));

const SESSION_HOURS = 12;
const OAUTH_STATE_MINUTES = 10;
const configuredTimeout = Number(process.env.DASHBOARD_AUTH_UPSTREAM_TIMEOUT_MS || 10_000);
const UPSTREAM_TIMEOUT_MS = Number.isFinite(configuredTimeout) && configuredTimeout > 0
  ? configuredTimeout : 10_000;
const MAX_JSON_BYTES = 1024 * 1024;

const consumedOAuthStates = new Map();
const revokedSessions = new Map();

let jwksCache = { keys: [], fetchedAt: 0 };

function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, (res) => {
      let body = "";
      res.on("data", (c) => {
        body += c;
        if (Buffer.byteLength(body) > MAX_JSON_BYTES) {
          req.destroy(new Error("OAuth upstream response exceeded the size limit"));
        }
      });
      res.on("end", () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          return reject(new Error(`OAuth upstream returned HTTP ${res.statusCode}`));
        }
        try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
      });
    });
    req.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
      req.destroy(new Error(`OAuth upstream timed out after ${UPSTREAM_TIMEOUT_MS}ms`)));
    req.on("error", reject);
  });
}

async function jwks() {
  // Cached for an hour. Google rotates these keys, so a permanently cached set would eventually reject
  // every valid token.
  if (Date.now() - jwksCache.fetchedAt < 3600_000 && jwksCache.keys.length) return jwksCache.keys;
  const doc = await getJson("https://www.googleapis.com/oauth2/v3/certs");
  jwksCache = { keys: doc.keys || [], fetchedAt: Date.now() };
  return jwksCache.keys;
}

function b64urlToBuf(s) {
  return Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64");
}

// Verifies signature, issuer, audience and expiry. Returns the email or throws.
export async function verifyIdToken(idToken, expectedNonce) {
  const [headB64, payloadB64, sigB64] = String(idToken).split(".");
  if (!headB64 || !payloadB64 || !sigB64) throw new Error("malformed id_token");

  const header = JSON.parse(b64urlToBuf(headB64).toString("utf8"));
  const payload = JSON.parse(b64urlToBuf(payloadB64).toString("utf8"));

  const key = (await jwks()).find((k) => k.kid === header.kid);
  if (!key) throw new Error("no matching signing key");

  const pub = crypto.createPublicKey({ key, format: "jwk" });
  const ok = crypto.verify(
    "RSA-SHA256",
    Buffer.from(`${headB64}.${payloadB64}`),
    pub,
    b64urlToBuf(sigB64),
  );
  if (!ok) throw new Error("bad signature");

  if (payload.aud !== CLIENT_ID) throw new Error("wrong audience");
  if (!["accounts.google.com", "https://accounts.google.com"].includes(payload.iss)) {
    throw new Error("wrong issuer");
  }
  if (typeof payload.exp !== "number" || payload.exp * 1000 < Date.now()) throw new Error("expired");
  if (!expectedNonce || !safeEqual(payload.nonce, expectedNonce)) throw new Error("wrong nonce");
  // An unverified email could be attacker-controlled on some account types.
  if (!payload.email || payload.email_verified !== true) throw new Error("unverified email");

  return payload.email.toLowerCase();
}

export function isAllowed(email) {
  return ALLOWED.includes(String(email).toLowerCase());
}

// --- session cookie ---------------------------------------------------------------------------------

function encryptCookiePayload(purpose, value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", COOKIE_KEY, iv);
  cipher.setAAD(Buffer.from(purpose, "utf8"));
  const ciphertext = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  return [iv, ciphertext, cipher.getAuthTag()].map((part) => part.toString("base64url")).join(".");
}

function decryptCookiePayload(purpose, value) {
  try {
    if (typeof value !== "string" || value.length > 4096) return null;
    const parts = value.split(".");
    if (parts.length !== 3) return null;
    const iv = Buffer.from(parts[0], "base64url");
    const ciphertext = Buffer.from(parts[1], "base64url");
    const tag = Buffer.from(parts[2], "base64url");
    if (iv.length !== 12 || tag.length !== 16) return null;
    if (parts[0] !== iv.toString("base64url")
        || parts[1] !== ciphertext.toString("base64url")
        || parts[2] !== tag.toString("base64url")) return null;
    const decipher = crypto.createDecipheriv("aes-256-gcm", COOKIE_KEY, iv);
    decipher.setAAD(Buffer.from(purpose, "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
  } catch {
    return null;
  }
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ""));
  const b = Buffer.from(String(right || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function cookieValue(cookieHeader, name) {
  return new RegExp(`(?:^|;\\s*)${name}=([^;]+)`).exec(cookieHeader || "")?.[1] || null;
}

function purgeExpired(map) {
  const now = Date.now();
  for (const [key, expiresAt] of map) {
    if (!(expiresAt > now)) map.delete(key);
  }
}

export function makeSession(email) {
  const expires = Date.now() + SESSION_HOURS * 3600_000;
  const sessionId = crypto.randomBytes(18).toString("base64url");
  return encryptCookiePayload("session", JSON.stringify({ email, expires, sessionId }));
}

export function readSession(cookieHeader) {
  purgeExpired(revokedSessions);
  const raw = cookieValue(cookieHeader, "ck_session");
  if (!raw) return null;
  if (revokedSessions.has(raw)) return null;
  const plaintext = decryptCookiePayload("session", raw);
  if (!plaintext) return null;
  let session;
  try {
    session = JSON.parse(plaintext);
  } catch {
    return null;
  }
  if (!session || typeof session.email !== "string" || typeof session.sessionId !== "string"
      || !(Number(session.expires) > Date.now())) return null;
  // Re-checked on every request, not just at sign-in: removing someone from the allowlist must take
  // effect on their next page load, not twelve hours later when their cookie expires.
  return isAllowed(session.email) ? session.email : null;
}

export function revokeSession(cookieHeader) {
  const raw = cookieValue(cookieHeader, "ck_session");
  if (!raw) return;
  const plaintext = decryptCookiePayload("session", raw);
  if (!plaintext) return;
  try {
    const session = JSON.parse(plaintext);
    const expiresAt = Number(session.expires);
    if (expiresAt > Date.now()) revokedSessions.set(raw, expiresAt);
  } catch {
    // Invalid cookies are already unauthenticated and need no server-side revocation entry.
  }
}

export const clearSessionCookie =
  "ck_session=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0";
export const expireAuthorizationCookie =
  "ck_auth_flow=; HttpOnly; Secure; SameSite=Lax; Path=/auth/callback; Max-Age=0";

// --- oauth ------------------------------------------------------------------------------------------

export function beginAuthorization(destination) {
  purgeExpired(consumedOAuthStates);
  const nonce = crypto.randomBytes(24).toString("base64url");
  const codeVerifier = crypto.randomBytes(32).toString("base64url");
  const codeChallenge = crypto.createHash("sha256").update(codeVerifier).digest("base64url");
  const expiresAt = Date.now() + OAUTH_STATE_MINUTES * 60_000;
  const value = encryptCookiePayload(
    "authorization-state", JSON.stringify({ nonce, destination, codeVerifier, expiresAt }));
  return {
    state: nonce,
    codeChallenge,
    cookie: `ck_auth_flow=${value}; HttpOnly; Secure; SameSite=Lax; Path=/auth/callback; Max-Age=${OAUTH_STATE_MINUTES * 60}`,
  };
}

export function consumeAuthorization(cookieHeader, receivedState) {
  purgeExpired(consumedOAuthStates);
  const raw = cookieValue(cookieHeader, "ck_auth_flow");
  if (!raw) throw new Error("OAuth state cookie is missing or expired");
  const plaintext = decryptCookiePayload("authorization-state", raw);
  if (!plaintext) throw new Error("OAuth state cookie is invalid");
  let payload;
  try {
    payload = JSON.parse(plaintext);
  } catch {
    throw new Error("OAuth state cookie is malformed");
  }
  if (!payload || typeof payload.nonce !== "string" || typeof payload.destination !== "string"
      || typeof payload.codeVerifier !== "string" || !(Number(payload.expiresAt) > Date.now())) {
    throw new Error("OAuth state cookie is missing required data or expired");
  }
  if (!safeEqual(receivedState, payload.nonce)) throw new Error("OAuth state did not match");
  if (consumedOAuthStates.has(payload.nonce)) throw new Error("OAuth state was already used");
  consumedOAuthStates.set(payload.nonce, Number(payload.expiresAt));
  return { destination: payload.destination, codeVerifier: payload.codeVerifier, nonce: payload.nonce };
}

export function authUrl(redirectUri, state, codeChallenge) {
  const p = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid email",
    state,
    nonce: state,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    prompt: "select_account",
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${p}`;
}

export function exchangeCode(code, redirectUri, codeVerifier) {
  const body = new URLSearchParams({
    code,
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    redirect_uri: redirectUri,
    grant_type: "authorization_code",
    code_verifier: codeVerifier,
  }).toString();

  return new Promise((resolve, reject) => {
    const req = https.request({
      host: "oauth2.googleapis.com",
      path: "/token",
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        "content-length": Buffer.byteLength(body),
      },
    }, (res) => {
      let out = "";
      res.on("data", (c) => {
        out += c;
        if (Buffer.byteLength(out) > MAX_JSON_BYTES) {
          req.destroy(new Error("OAuth token response exceeded the size limit"));
        }
      });
      res.on("end", () => {
        try {
          const parsed = JSON.parse(out);
          if (res.statusCode < 200 || res.statusCode >= 300) {
            return reject(new Error(
              parsed.error_description || parsed.error || `OAuth token endpoint returned HTTP ${res.statusCode}`));
          }
          if (parsed.error) return reject(new Error(parsed.error_description || parsed.error));
          resolve(parsed);
        } catch (e) { reject(e); }
      });
    });
    req.on("error", reject);
    req.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
      req.destroy(new Error(`OAuth token exchange timed out after ${UPSTREAM_TIMEOUT_MS}ms`)));
    req.end(body);
  });
}

export const configured = Boolean(CLIENT_ID && CLIENT_SECRET);
export const allowlistSize = ALLOWED.length;
