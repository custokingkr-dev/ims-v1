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
// Signs the session cookie. Generated per revision when unset, which logs everyone out on deploy --
// acceptable for a dashboard, and far better than a hardcoded default that would let anyone who read
// this file mint their own session.
const COOKIE_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");

const SESSION_HOURS = 12;

let jwksCache = { keys: [], fetchedAt: 0 };

function getJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      let body = "";
      res.on("data", (c) => (body += c));
      res.on("end", () => {
        try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
      });
    }).on("error", reject);
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
export async function verifyIdToken(idToken) {
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
  // An unverified email could be attacker-controlled on some account types.
  if (!payload.email || payload.email_verified !== true) throw new Error("unverified email");

  return payload.email.toLowerCase();
}

export function isAllowed(email) {
  return ALLOWED.includes(String(email).toLowerCase());
}

// --- session cookie ---------------------------------------------------------------------------------

function sign(value) {
  return crypto.createHmac("sha256", COOKIE_SECRET).update(value).digest("base64url");
}

export function makeSession(email) {
  const expires = Date.now() + SESSION_HOURS * 3600_000;
  const value = `${Buffer.from(email).toString("base64url")}.${expires}`;
  return `${value}.${sign(value)}`;
}

export function readSession(cookieHeader) {
  const raw = /(?:^|;\s*)ck_session=([^;]+)/.exec(cookieHeader || "")?.[1];
  if (!raw) return null;
  const parts = raw.split(".");
  if (parts.length !== 3) return null;
  const value = `${parts[0]}.${parts[1]}`;
  // timingSafeEqual rather than === so the comparison does not leak the signature a byte at a time.
  const expected = sign(value);
  const a = Buffer.from(parts[2]);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  if (Number(parts[1]) < Date.now()) return null;
  const email = Buffer.from(parts[0], "base64url").toString("utf8");
  // Re-checked on every request, not just at sign-in: removing someone from the allowlist must take
  // effect on their next page load, not twelve hours later when their cookie expires.
  return isAllowed(email) ? email : null;
}

// --- oauth ------------------------------------------------------------------------------------------

export function authUrl(redirectUri, state) {
  const p = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid email",
    state,
    prompt: "select_account",
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${p}`;
}

export function exchangeCode(code, redirectUri) {
  const body = new URLSearchParams({
    code,
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    redirect_uri: redirectUri,
    grant_type: "authorization_code",
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
      res.on("data", (c) => (out += c));
      res.on("end", () => {
        try {
          const parsed = JSON.parse(out);
          if (parsed.error) return reject(new Error(parsed.error_description || parsed.error));
          resolve(parsed);
        } catch (e) { reject(e); }
      });
    });
    req.on("error", reject);
    req.end(body);
  });
}

export const configured = Boolean(CLIENT_ID && CLIENT_SECRET);
export const allowlistSize = ALLOWED.length;
