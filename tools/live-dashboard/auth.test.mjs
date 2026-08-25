import assert from "node:assert/strict";
import test from "node:test";

process.env.OAUTH_CLIENT_ID = "dashboard-test-client";
process.env.OAUTH_CLIENT_SECRET = "dashboard-test-secret";
process.env.DASHBOARD_ALLOWED_EMAILS = "owner@example.com";
process.env.SESSION_SECRET = "test-only-".repeat(8);

const auth = await import("./auth.mjs?auth-regression-tests");

function cookieHeader(setCookie) {
  return setCookie.split(";", 1)[0];
}

function encryptedToken(setCookie) {
  const token = cookieHeader(setCookie).split("=", 2)[1];
  const segments = token.split(".");
  assert.equal(segments.length, 3);
  assert.equal(Buffer.from(segments[0], "base64url").length, 12);
  assert.equal(Buffer.from(segments[2], "base64url").length, 16);
  return { token, decoded: segments.map((segment) =>
    Buffer.from(segment, "base64url").toString("utf8")).join("|") };
}

function tamper(value) {
  return `${value.slice(0, -1)}${value.endsWith("A") ? "B" : "A"}`;
}

test("OAuth state is unique, browser-bound, PKCE-protected and single use", () => {
  const first = auth.beginAuthorization("/ops");
  const second = auth.beginAuthorization("/ops");

  assert.notEqual(first.state, second.state);
  assert.notEqual(first.codeChallenge, second.codeChallenge);
  assert.match(first.cookie, /HttpOnly; Secure; SameSite=Lax/);

  const location = new URL(auth.authUrl(
    "https://dashboard.example/auth/callback", first.state, first.codeChallenge));
  assert.equal(location.searchParams.get("state"), first.state);
  assert.equal(location.searchParams.get("nonce"), first.state);
  assert.equal(location.searchParams.get("code_challenge"), first.codeChallenge);
  assert.equal(location.searchParams.get("code_challenge_method"), "S256");

  const consumed = auth.consumeAuthorization(cookieHeader(first.cookie), first.state);
  assert.equal(consumed.destination, "/ops");
  assert.ok(consumed.codeVerifier.length >= 43);
  const protectedFlow = encryptedToken(first.cookie);
  assert.equal(protectedFlow.decoded.includes(consumed.codeVerifier), false);
  assert.equal(protectedFlow.decoded.includes("/ops"), false);
  assert.match(first.codeChallenge, /^[A-Za-z0-9_-]{43}$/);
  assert.throws(
    () => auth.consumeAuthorization(cookieHeader(first.cookie), first.state),
    /already used/,
  );
});

test("OAuth callback rejects missing, mismatched and tampered state", () => {
  const mismatch = auth.beginAuthorization("/owner");
  assert.throws(() => auth.consumeAuthorization("", mismatch.state), /missing or expired/);
  assert.throws(
    () => auth.consumeAuthorization(cookieHeader(mismatch.cookie), "attacker-state"),
    /did not match/,
  );

  const tampered = tamper(cookieHeader(mismatch.cookie));
  assert.throws(() => auth.consumeAuthorization(tampered, mismatch.state), /invalid/);
});

test("encrypted sessions are allowlisted, tamper evident and explicitly revocable", () => {
  const session = auth.makeSession("owner@example.com");
  assert.equal(encryptedToken(`ck_session=${session}`).decoded.includes("owner@example.com"), false);
  const header = `ck_session=${session}`;
  assert.equal(auth.readSession(header), "owner@example.com");
  assert.equal(auth.readSession(tamper(header)), null);

  auth.revokeSession(header);
  assert.equal(auth.readSession(header), null);
  assert.match(auth.clearSessionCookie, /Max-Age=0/);
});

test("an authenticated identity removed from the allowlist is denied", () => {
  const session = auth.makeSession("removed@example.com");
  assert.equal(auth.readSession(`ck_session=${session}`), null);
});
