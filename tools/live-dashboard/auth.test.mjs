import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";

process.env.OAUTH_CLIENT_ID = "dashboard-test-client";
process.env.OAUTH_CLIENT_SECRET = "dashboard-test-secret";
process.env.DASHBOARD_ALLOWED_EMAILS = "owner@example.com";
process.env.SESSION_SECRET = "test-only-".repeat(8);

const auth = await import("./auth.mjs?auth-regression-tests");

function cookieHeader(setCookie) {
  return setCookie.split(";", 1)[0];
}

test("OAuth state is unique, browser-bound, PKCE-protected and single use", () => {
  const first = auth.beginOAuth("/ops");
  const second = auth.beginOAuth("/ops");

  assert.notEqual(first.state, second.state);
  assert.notEqual(first.codeChallenge, second.codeChallenge);
  assert.match(first.cookie, /HttpOnly; Secure; SameSite=Lax/);

  const location = new URL(auth.authUrl(
    "https://dashboard.example/auth/callback", first.state, first.codeChallenge));
  assert.equal(location.searchParams.get("state"), first.state);
  assert.equal(location.searchParams.get("nonce"), first.state);
  assert.equal(location.searchParams.get("code_challenge"), first.codeChallenge);
  assert.equal(location.searchParams.get("code_challenge_method"), "S256");

  const consumed = auth.consumeOAuth(cookieHeader(first.cookie), first.state);
  assert.equal(consumed.destination, "/ops");
  assert.ok(consumed.codeVerifier.length >= 43);
  assert.equal(
    crypto.createHash("sha256").update(consumed.codeVerifier).digest("base64url"),
    first.codeChallenge,
  );
  assert.throws(
    () => auth.consumeOAuth(cookieHeader(first.cookie), first.state),
    /already used/,
  );
});

test("OAuth callback rejects missing, mismatched and tampered state", () => {
  const mismatch = auth.beginOAuth("/owner");
  assert.throws(() => auth.consumeOAuth("", mismatch.state), /missing or expired/);
  assert.throws(
    () => auth.consumeOAuth(cookieHeader(mismatch.cookie), "attacker-state"),
    /did not match/,
  );

  const tampered = cookieHeader(mismatch.cookie).replace(/.$/, "x");
  assert.throws(() => auth.consumeOAuth(tampered, mismatch.state), /invalid/);
});

test("signed sessions are allowlisted, tamper evident and explicitly revocable", () => {
  const session = auth.makeSession("owner@example.com");
  const header = `ck_session=${session}`;
  assert.equal(auth.readSession(header), "owner@example.com");
  assert.equal(auth.readSession(`${header}x`), null);

  auth.revokeSession(header);
  assert.equal(auth.readSession(header), null);
  assert.match(auth.clearSessionCookie, /Max-Age=0/);
});

test("an authenticated identity removed from the allowlist is denied", () => {
  const session = auth.makeSession("removed@example.com");
  assert.equal(auth.readSession(`ck_session=${session}`), null);
});
