import assert from 'node:assert/strict';
import test from 'node:test';
import http from 'node:http';
import { securityConfiguration } from './security-config.mjs';
import { memorySecurityState, firestoreSecurityState } from './security-state.mjs';
import { queryParameters, concurrencyBudget, snapshotCache, wholeResponseDeadline } from './query-budget.mjs';

test('deployed profiles refuse auth-off, weak secrets, unpinned origins and absent durable state', () => {
  const env = { K_SERVICE: 'dashboard', DASHBOARD_AUTH: 'on', SESSION_SECRET: 'x'.repeat(32),
    DASHBOARD_PROJECT: 'test-project', DASHBOARD_STATE_DATABASE: 'dashboard-dev', DASHBOARD_PUBLIC_URL: 'https://dashboard.example' };
  assert.equal(securityConfiguration(env).deployed, true);
  for (const bad of [{ DASHBOARD_AUTH: 'off' }, { SESSION_SECRET: '' }, { SESSION_SECRET: 'short' },
    { DASHBOARD_PUBLIC_URL: '' }, { DASHBOARD_PUBLIC_URL: 'http://dashboard.example' },
    { DASHBOARD_PUBLIC_URL: 'https://user:password@dashboard.example' }, { DASHBOARD_PUBLIC_URL: 'https://dashboard.example/path' },
    { DASHBOARD_STATE_DATABASE: '' }]) assert.throws(() => securityConfiguration({ ...env, ...bad }));
  assert.equal(securityConfiguration({ DASHBOARD_AUTH: 'off' }).authRequired, false);
});

test('Firestore atomic create shares replay/revocation across independent store instances and fails closed', async () => {
  const documents = new Set();
  const fetchImpl = async (url, request) => {
    assert.match(url, /databases\/dashboard-dev\/documents\/dashboardSecurityState/);
    assert.equal(request.headers.authorization, 'Bearer test-token');
    const uri = new URL(url);
    const name = request.method === 'POST' ? uri.searchParams.get('documentId') : uri.pathname.split('/').at(-1);
    if (request.method === 'POST') {
      assert.ok(JSON.parse(request.body).fields.expiresAt.timestampValue);
      if (documents.has(name)) return new Response('{}', { status: 409 });
      documents.add(name); return new Response('{}', { status: 200 });
    }
    return new Response('{}', { status: documents.has(name) ? 200 : 404 });
  };
  const options = { project: 'test-project', database: 'dashboard-dev', accessToken: async () => 'test-token', fetchImpl };
  const first = firestoreSecurityState(options), replica = firestoreSecurityState(options);
  const results = await Promise.all([first.claim('consumed', 'nonce', Date.now()+1000), replica.claim('consumed', 'nonce', Date.now()+1000)]);
  assert.deepEqual(results.sort(), [false, true]);
  await first.claim('revoked', 'cookie-secret-value', Date.now()+1000);
  assert.equal(await replica.contains('revoked', 'cookie-secret-value'), true);
  assert.equal(await replica.contains('revoked', 'different-session'), false);
  const unavailable = firestoreSecurityState({ ...options, fetchImpl: async () => new Response('{}', { status: 503 }) });
  await assert.rejects(unavailable.contains('revoked', 'cookie'), /unavailable/);
  assert.throws(() => firestoreSecurityState({ ...options, database: '(default)' }));
});

test('snapshot query keys are bounded and coalescing prevents repeated query fanout', async () => {
  for (const query of ['window=NaN', 'window=Infinity', 'window=-1', 'window=999999999', 'audience=unknown'])
    assert.throws(() => queryParameters(new URL(`https://example.test/?${query}`)));
  assert.deepEqual(queryParameters(new URL('https://example.test/?window=720&audience=ops')), { windowMinutes: 720, audience: 'ops' });
  let calls = 0; const cached = snapshotCache();
  const results = await Promise.all(Array.from({length:20}, () => cached(180, 'ops', async () => { calls++; return { result: 'same' }; })));
  assert.equal(calls, 1); assert.equal(results.length, 20);
});

test('monitoring admission caps concurrency and abandons queued cancelled work', async () => {
  const run = concurrencyBudget(2, 2); let current=0, maximum=0;
  const task = async () => { current++; maximum=Math.max(maximum,current); await new Promise(r => setTimeout(r,10)); current--; };
  await Promise.all(Array.from({length:4}, () => run(task)));
  assert.equal(maximum, 2);
  const controller = new AbortController();
  const a=run(task), b=run(task), cancelled=run(task, controller.signal);
  controller.abort(); await assert.rejects(cancelled, /cancelled/); await Promise.all([a,b]);
});

test('entire response deadline cancels a continuously trickling body', async (t) => {
  const server = http.createServer((req,res) => {
    res.writeHead(200); const timer = setInterval(() => res.write('x'),20);
    res.once('close', () => clearInterval(timer));
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const start=Date.now();
  await assert.rejects(new Promise((resolve,reject) => {
    const request=http.get(`http://127.0.0.1:${server.address().port}`, response => { response.resume(); response.on('end',resolve); response.on('error',reject); });
    wholeResponseDeadline(request,150); request.on('error',reject);
  }), /deadline|aborted/);
  assert.ok(Date.now()-start<1000);
});

test('async authenticated capability checks share replay and logout revocation between replicas', async () => {
  process.env.SESSION_SECRET='stable-managed-test-secret-'.repeat(3);
  process.env.DASHBOARD_ALLOWED_EMAILS='owner@example.com';
  const first=await import('./auth.mjs?shared-state-replica-one');
  const second=await import('./auth.mjs?shared-state-replica-two');
  const store=memorySecurityState(); first.configureSecurityState(store); second.configureSecurityState(store);
  const flow=first.beginAuthorization('/ops'); const flowCookie=flow.cookie.split(';')[0];
  const outcomes=await Promise.allSettled([first.consumeAuthorizationAsync(flowCookie,flow.state),second.consumeAuthorizationAsync(flowCookie,flow.state)]);
  assert.equal(outcomes.filter(value=>value.status==='fulfilled').length,1);
  const session=`ck_session=${first.makeSession('owner@example.com')}`;
  assert.equal(await second.readSessionAsync(session),'owner@example.com');
  await first.revokeSessionAsync(session);
  assert.equal(await second.readSessionAsync(session),null);
});
