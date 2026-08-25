import assert from "node:assert/strict";
import test from "node:test";

process.env.DASHBOARD_AUTH = "on";
process.env.OAUTH_CLIENT_ID = "dashboard-test-client";
process.env.OAUTH_CLIENT_SECRET = "dashboard-test-secret";
process.env.DASHBOARD_ALLOWED_EMAILS = "owner@example.com";
process.env.SESSION_SECRET = "test-only-".repeat(8);

const { server } = await import("./server.mjs?server-regression-tests");

test("server binds sign-in state, rejects unbound callbacks, and clears logout cookies", async (t) => {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();

  const origin = `http://127.0.0.1:${address.port}`;
  const signIn = await fetch(`${origin}/ops`, { redirect: "manual" });
  assert.equal(signIn.status, 302);
  assert.match(signIn.headers.get("set-cookie"), /ck_oauth_state=.*HttpOnly/);
  const authorization = new URL(signIn.headers.get("location"));
  assert.equal(authorization.hostname, "accounts.google.com");
  assert.equal(authorization.searchParams.get("code_challenge_method"), "S256");
  assert.ok(authorization.searchParams.get("state"));
  assert.equal(
    authorization.searchParams.get("nonce"),
    authorization.searchParams.get("state"),
  );

  const unboundCallback = await fetch(`${origin}/auth/callback?code=test&state=attacker`, {
    redirect: "manual",
  });
  assert.equal(unboundCallback.status, 400);
  assert.match(await unboundCallback.text(), /state cookie is missing/);

  const response = await fetch(`${origin}/auth/logout`, {
    method: "POST",
    redirect: "manual",
    headers: { cookie: "ck_session=invalid" },
  });

  assert.equal(response.status, 303);
  assert.equal(response.headers.get("location"), "/owner");
  assert.match(response.headers.get("set-cookie"), /ck_session=.*Max-Age=0/);
  assert.match(response.headers.get("set-cookie"), /ck_oauth_state=.*Max-Age=0/);
  assert.equal(response.headers.get("cache-control"), "no-store");
});
