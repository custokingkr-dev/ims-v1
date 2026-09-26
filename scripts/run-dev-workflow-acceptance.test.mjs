import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { inflateSync } from 'node:zlib';
import { Api, Journal, parseArgs, plan, execute, SYNTHETIC_PNG, feePublicationSnapshot, proveFeePublication } from './run-dev-workflow-acceptance.mjs';

const options = directory => ({ project: 'custoking-dev', runId: 'product-20260926-a1', apply: true, directory });
function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'ims-dev-acceptance-test-'));
  t.after(() => {
    assert.equal(dirname(resolve(directory)), resolve(tmpdir()));
    assert.ok(basename(directory).startsWith('ims-dev-acceptance-test-'));
    rmSync(directory, { recursive: true, force: true });
  });
  return { directory, path: join(directory, 'journal.json'), journal: new Journal(join(directory, 'journal.json'), 'test-run') };
}

test('plan rejects other projects, defaults to no writes, and fixes school/gateway', () => {
  assert.throws(() => parseArgs(['--project', 'custoking-prod', '--run-id', 'test-run']), /EXPLICIT_DEV_PROJECT/);
  assert.throws(() => parseArgs(['--project', 'custoking-dev', '--run-id', '../../escape']), /STABLE_RUN_ID/);
  const p = plan(parseArgs(['--project', 'custoking-dev', '--run-id', 'test-run']));
  assert.equal(p.apply, false); assert.equal(p.schoolId, 1);
  assert.equal(p.cleanupRequestReserve, 15); assert.equal(p.businessDeadlineMinutes, 15);
});

test('intent is flushed before sending; uncertain create must reconcile and cannot silently retry', async t => {
  const { journal: j, path } = fixture(t); let sends = 0;
  const request = { intent: { method: 'POST', endpoint: '/students', marker: 'synthetic' },
    send: async () => { sends++; const disk = JSON.parse(readFileSync(path)); assert.equal(disk.operations.create.state, 'uncertain'); assert.equal(disk.operations.create.intent.marker, 'synthetic'); throw new Error('lost response'); },
    prove: () => ({ id: 1 }) };
  await assert.rejects(j.mutate('create', request));
  await assert.rejects(j.mutate('create', { ...request, reconcile: async () => null }), /UNCERTAIN_NON_IDEMPOTENT/);
  assert.equal(sends, 1);
  assert.deepEqual(await j.mutate('create', { ...request, reconcile: async () => ({ id: 42 }) }), { id: 42 });
  assert.equal(sends, 1);
});

test('same payment intent can replay after timeout; changed intent cannot reuse a journal entry', async t => {
  const { journal: j } = fixture(t); let sends = 0;
  const request = { intent: { idempotencyKey: 'stable', amount: 100 }, replaySafe: true,
    send: async () => { if (++sends === 1) throw new Error('lost'); return { status: 200 }; }, prove: () => ({ paymentId: 'same' }) };
  await assert.rejects(j.mutate('pay', request));
  assert.deepEqual(await j.mutate('pay', request), { paymentId: 'same' });
  await assert.rejects(j.mutate('pay', { ...request, intent: { idempotencyKey: 'stable', amount: 101 } }), /JOURNAL_INTENT_CHANGED/);
  assert.equal(sends, 2);
});

function publicationFixture() {
  return { id: 'owned-band', schoolId: 1, academicYearId: 'ay_2026_27', name: 'Synthetic acceptance test-run',
    status: 'PUBLISHED', classFrom: 1, classTo: 1, discount: 0, activeSchedules: ['Annual'], annualTotal: 100,
    gracePeriodDays: 0, lateFeeType: 'NONE', lateFeeAmount: 0, lateFeeIntervalDays: 0,
    items: [{ id: 'owned-item', name: 'Synthetic fee test-run', frequency: 'Annual', amount: 100, optional: false }],
    installments: [{ label: 'Synthetic test-run', dueDate: '2026-09-26', sharePercent: 100 }] };
}

test('first and already-published response shapes prove the same exact owned fee snapshot', () => {
  const band = publicationFixture(); const expected = feePublicationSnapshot(band);
  const initial = proveFeePublication({ status: 200, data: { ok: true, actorId: 42, band } }, expected);
  const replay = proveFeePublication({ status: 200, data: { ...band, publishedAt: '2026-09-26T12:00:00Z' } }, expected);
  assert.deepEqual(initial, replay);
  assert.equal(replay.id, 'owned-band'); assert.match(replay.fingerprint, /^[a-f0-9]{64}$/);
  for (const change of [
    b => { b.id = 'other-band'; }, b => { b.schoolId = 2; }, b => { b.academicYearId = 'ay_2025_26'; },
    b => { b.items[0].id = 'other-item'; }, b => { b.items[0].amount = 101; },
    b => { b.installments[0].sharePercent = 50; }, b => { b.lateFeeType = 'DAILY'; },
    b => { b.activeSchedules = ['Monthly']; }, b => { b.classTo = 2; },
  ]) {
    const changed = structuredClone(band); change(changed);
    assert.throws(() => proveFeePublication({ status: 200, data: changed }, expected), /OWNERSHIP_OR_CONTENT/);
  }
  assert.throws(() => proveFeePublication({ status: 200, data: { ...band, status: 'DRAFT' } }, expected), /NOT_CONFIRMED/);
});

test('lost publication response recovers the matching published band without another mutation', async t => {
  const { journal: j } = fixture(t); const band = publicationFixture(); const expected = feePublicationSnapshot(band);
  let published = false, sends = 0;
  const request = { intent: { method: 'POST', endpoint: '/fees/bands/owned-band/publish', snapshot: expected }, replaySafe: true,
    reconcile: async () => published ? proveFeePublication({ status: 200, data: band }, expected) : null,
    send: async () => { sends++; published = true; throw new Error('lost after commit'); },
    prove: response => proveFeePublication(response, expected) };
  await assert.rejects(j.mutate('fee-publish', request));
  const recovered = await j.mutate('fee-publish', request);
  assert.equal(sends, 1); assert.equal(recovered.id, 'owned-band');
  assert.equal(j.data.operations['fee-publish'].reconciled, true);
  assert.equal(j.data.operations['fee-publish'].state, 'confirmed');
});

test('later validation failure never clears an earlier uncertain mutation', async t => {
  const { journal: j } = fixture(t); let status = 0;
  const request = { intent: { idempotencyKey: 'original' }, replaySafe: true,
    send: async () => { if (!status) throw new Error('lost'); return { status }; },
    prove: response => { assert.equal(response.status, 200); return { id: 1 }; } };
  await assert.rejects(j.mutate('mutation', request)); status = 400;
  await assert.rejects(j.mutate('mutation', request));
  assert.equal(j.data.operations.mutation.state, 'uncertain');
  assert.equal(j.data.operations.mutation.intent.idempotencyKey, 'original');
});

test('credential-bearing intent is rejected before a write or disk persistence', async t => {
  const { journal: j, path } = fixture(t); let sent = false;
  await assert.rejects(j.mutate('bad', { intent: { body: { temporaryPassword: 'DO-NOT-SAVE' } }, send: async () => { sent = true; }, prove: () => ({}) }), /CREDENTIAL_FIELD/);
  assert.equal(sent, false); assert.equal(existsSync(path), false);
});

test('business request exhaustion and deadline both preserve cleanup reserve', async t => {
  const { journal: j } = fixture(t); let calls = 0;
  j.requestCount = 145; j.deadline = Date.now() + 10000; j.cleanupMode = false;
  j.fetchImpl = async () => { calls++; return new Response(null, { status: 204 }); };
  const client = new Api(j, 'TEST');
  await assert.rejects(client.request('POST', '/auth/logout'), /REQUEST_BUDGET/);
  assert.equal(j.requestCount, 145); assert.equal(calls, 0);
  j.requestCount = 0; j.deadline = 0;
  await assert.rejects(client.request('POST', '/auth/logout'), /BUSINESS_DEADLINE/);
  j.cleanupMode = true; j.requestCount = 145;
  for (let i = 0; i < 15; i++) await client.request('POST', '/auth/logout');
  assert.equal(calls, 15);
  await assert.rejects(client.request('POST', '/auth/logout'), /REQUEST_BUDGET/);
});

function fakeServer({ lostAdminCreate = false, wrongAdminScope = false, foreignFeeBand = false } = {}) {
  let owner = null; const calls = [];
  const run = 'product-20260926-a1';
  const date = new Date(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) + 'T12:00:00Z');
  const yearStart = date.getUTCFullYear() - (date.getUTCMonth() < 3 ? 1 : 0);
  const year = `ay_${yearStart}_${String(yearStart + 1).slice(-2)}`;
  const response = (data, status = 200) => new Response(data === null ? null : JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
  return { calls, get owner() { return owner; }, fetch: async (url, init) => {
    const u = new URL(url), path = u.pathname.replace('/api/v1', ''); calls.push({ path, method: init.method });
    if (path === '/auth/login') {
      const body = JSON.parse(init.body); const school = body.email.endsWith('@synthetic.invalid');
      return response({ role: school ? 'ADMIN' : 'SUPERADMIN', branchId: school ? wrongAdminScope ? 2 : 1 : null,
        userId: school ? 42 : 10, accessToken: 'TOKEN-MUST-NOT-APPEAR' });
    }
    if (path === '/auth/logout') return response(null, 204);
    if (path === '/schools/1') return response({ id: 1, active: true, name: 'Local Demo School', academicYearStartMonth: 4 });
    if (path === '/academic-years') return response([{ id: year }]);
    if (path === '/schools/1/modules/active') return response(['STUDENTS', 'ATTENDANCE', 'FEES', 'FIREFIGHTING', 'ORDERS'].map(code => ({ code })));
    if (path === '/ff/quotation-documents/capabilities') return response({ available: true, canUpload: true });
    if (path === '/catalog/annual-plan/review') return response({ schoolId: 1, academicYearId: year, items: [] });
    if (path === '/fees/structure') return response({ academicYearId: year, bands: foreignFeeBand ? [{ name: 'Existing school plan' }] : [] });
    if (path === '/users') return response(owner ? [owner] : []);
    if (path === '/schools/1/admin') {
      const body = JSON.parse(init.body);
      assert.match(body.temporaryPassword, /^Aa7!/);
      owner = { id: 42, fullName: `Synthetic acceptance ${run}`, email: `acceptance-${run}@synthetic.invalid`, role: 'ADMIN', branchId: 1, active: true };
      if (lostAdminCreate) throw new Error('network lost after commit');
      return response({ userId: 42, ...owner });
    }
    if (path === '/users/42') return response(owner);
    if (path === '/users/42/disable') { owner.active = false; return response(null, 204); }
    throw new Error('UNEXPECTED_MOCK_ROUTE');
  } };
}

test('lost admin creation response reconciles the exact account and disables it in finally', async t => {
  const { directory } = fixture(t); const server = fakeServer({ lostAdminCreate: true });
  const result = await execute(options(directory), { credentialsReader: () => ({ email: 'bootstrap@example.invalid', password: 'PASSWORD-MUST-NOT-APPEAR' }), fetchImpl: server.fetch });
  assert.equal(result.completed, false); assert.equal(result.cleanup.administratorDisabled, true);
  assert.equal(server.owner.active, false);
  const journal = readFileSync(result.journal, 'utf8');
  assert.doesNotMatch(journal, /TOKEN-MUST-NOT-APPEAR|PASSWORD-MUST-NOT-APPEAR|temporaryPassword|Bearer|bootstrap@example/);
  assert.equal(server.calls.filter(c => c.path === '/schools/1/admin').length, 1);
  assert.equal(server.calls.filter(c => c.path === '/users/42/disable').length, 1);
});

test('school login scope mismatch stops all business writes and cleans up both sessions', async t => {
  const { directory } = fixture(t); const server = fakeServer({ wrongAdminScope: true });
  const result = await execute(options(directory), { credentialsReader: () => ({ email: 'bootstrap@example.invalid', password: 'memory' }), fetchImpl: server.fetch });
  assert.equal(result.failure, 'SCHOOL_ACTOR_SCOPE_MISMATCH');
  assert.equal(result.cleanup.administratorDisabled, true);
  assert.equal(server.calls.filter(c => c.path === '/auth/logout').length, 2);
  assert.equal(server.calls.some(c => c.path === '/students'), false);
});

test('existing non-owned fee band blocks before provisioning or business mutations', async t => {
  const { directory } = fixture(t); const server = fakeServer({ foreignFeeBand: true });
  const result = await execute(options(directory), { credentialsReader: () => ({ email: 'bootstrap@example.invalid', password: 'memory' }), fetchImpl: server.fetch });
  assert.equal(result.failure, 'EXISTING_FEE_BANDS_NOT_RUN_OWNED');
  assert.equal(server.calls.some(c => c.path === '/schools/1/admin'), false);
  assert.equal(result.cleanup.superadminLogoutConfirmed, true);
});

test('synthetic PNG has valid chunk checksums and a decodable one-pixel scanline', () => {
  const b = SYNTHETIC_PNG; assert.equal(b.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  let o = 8; let pixels;
  while (o < b.length) {
    const size = b.readUInt32BE(o), data = b.subarray(o + 4, o + 8 + size); let crc = 0xffffffff;
    for (const byte of data) { crc ^= byte; for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    assert.equal((crc ^ 0xffffffff) >>> 0, b.readUInt32BE(o + 8 + size));
    if (data.subarray(0, 4).toString() === 'IDAT') pixels = inflateSync(data.subarray(4));
    o += size + 12;
  }
  assert.equal(pixels.length, 3);
});
