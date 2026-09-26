import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { spawnSync } from 'node:child_process';
import { buildAcceptancePlan, assessCapacityReadiness, DEV_GATEWAY } from '../prepare-dev-product-acceptance.mjs';

const config = { project: 'custoking-dev', baseUrl: DEV_GATEWAY, schoolId: 1, runId: 'ACPT-20260926-abc123', classId: 'fixture-class', feeBandId: 'published-band', feeSchedule: 'Annual', academicYearId: '2026-27', attendanceDate: '2026-09-26' };
const now = new Date('2026-09-26T15:20:00Z');
const ready = () => ({ project: 'custoking-dev', instance: 'custoking-db-dev', billing: {
  scopeProject: 'custoking-dev', currency: 'INR', source: 'detailed-billing-export', latestExportAt: '2026-09-26T14:00:00Z', latestUsageAt: '2026-09-26T12:00:00Z', grossMonthInr: 1000, budgetInr: 2000, estimatedRunInr: 15,
}, logging: { monthGib: 1, estimatedRunGib: 0.01, observedAt: now.toISOString() }, database: { state: 'RUNNABLE', cpuRatio: 0.1, memoryUsagePercent: 15, connections: 8, observedAt: now.toISOString() }, fixture: { schoolId: 900000000, verifiedSynthetic: true } });

test('rejects other environments, unscoped fixtures, malformed dates and missing financial reference inputs', () => {
  for (const change of [{ project: 'custoking-prod' }, { baseUrl: `${DEV_GATEWAY}.attacker.test` }, { schoolId: 0 }, { runId: 'normal-school-work' }, { classId: '' }, { feeBandId: '' }, { feeSchedule: 'anything' }, { attendanceDate: '2026-02-30' }]) {
    assert.throws(() => buildAcceptancePlan({ ...config, ...change }));
  }
});

test('plan has no delivery endpoints, creates one contact-free synthetic student, and writes fees only against that captured id', () => {
  const plan = buildAcceptancePlan(config);
  const steps = [...plan.preflight, ...plan.phases.flatMap(p => p.steps)];
  assert.equal(plan.mode, 'PLAN_ONLY_NO_NETWORK');
  assert.ok(steps.length < plan.limits.maxHttpRequests);
  assert.ok(steps.every(s => s.path.startsWith('/api/v1/') && !/notify|reminder|broadcast|fulfill|vendor-paid|submit-day/.test(s.path)));
  const student = steps.find(s => s.id === 'student-create');
  assert.match(student.body.admissionNumber, /^ACPT-/);
  for (const field of ['id', 'studentId', 'sectionId', 'email', 'phone', 'fatherContact', 'fatherContactNumber', 'photoUrl']) assert.equal(student.body[field], undefined);
  for (const step of steps.filter(s => s.path.startsWith('/api/v1/fees/') && s.method === 'POST')) assert.equal(step.body.studentId, '${student.id}');
  assert.match(plan.phases.find(p => p.name === 'annual-plan').gate, /BLOCK/);
});

test('retries use exactly the original payload/key and conflicts differ only in the intended changed field', () => {
  const steps = buildAcceptancePlan(config).phases.flatMap(p => p.steps);
  for (const [first, replay, conflict, field] of [['fee-collect', 'fee-replay', 'fee-conflict', 'amount'], ['request-create', 'request-replay', 'request-conflict', 'title'], ['quote-create', 'quote-replay', 'quote-conflict', 'amount']]) {
    const original = steps.find(s => s.id === first).body;
    assert.deepEqual(steps.find(s => s.id === replay).body, original);
    const changed = { ...steps.find(s => s.id === conflict).body };
    assert.notEqual(changed[field], original[field]);
    delete changed[field];
    const compare = { ...original }; delete compare[field];
    assert.deepEqual(changed, compare);
  }
});

test('principal preflight uses the login response and a real authorized school read', () => {
  const plan = buildAcceptancePlan(config);
  assert.match(plan.principalPreflight.source, /POST \/api\/v1\/auth\/login/);
  assert.match(plan.principalPreflight.safety, /before any write/);
  assert.deepEqual(plan.preflight[0], {
    id: 'school-scope', method: 'GET', path: '/api/v1/schools/1',
    expect: ['Returned school id equals schoolId; authorized gateway read succeeds for the checked login principal.'], safety: [],
  });
  assert.ok(plan.preflight.every(step => !step.path.startsWith('/api/v1/auth/')));
  assert.match(readFileSync('services/identity-service/src/main/java/com/custoking/ims/identityservice/api/AuthController.java', 'utf8'), /@PostMapping\("\/login"\)/);
  assert.match(readFileSync('services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TenantSchoolController.java', 'utf8'), /@GetMapping\("\/schools\/\{id\}"\)/);
});

test('actual current gross cost blocks even when credits make net spend nearly zero', () => {
  const data = ready(); data.billing.grossMonthInr = 1966.3141; data.billing.netMonthInr = -0.0019;
  const assessment = assessCapacityReadiness(data, now);
  assert.equal(assessment.allowed, false);
  assert.ok(assessment.blockers.some(reason => reason.includes('80% budget')));
  assert.equal(assessment.budgetOverrideSupported, false);
});

test('cost, monitoring and synthetic-fixture gates fail closed on absent, stale or malformed evidence', () => {
  for (const mutate of [
    x => { x.billing.latestExportAt = '2026-08-23T20:36:21Z'; },
    x => { x.billing.latestUsageAt = '2026-09-26 12:00:00'; },
    x => { x.billing.grossMonthInr = null; },
    x => { x.billing.scopeProject = 'custoking-prod'; },
    x => { x.logging.observedAt = 'brokenZ'; },
    x => { x.logging.monthGib = 40; },
    x => { x.database.observedAt = '2026-09-26T15:10:00Z'; },
    x => { x.database.cpuRatio = 0.8; },
    x => { x.database.connections = 140; },
    x => { x.fixture.schoolId = 1; },
  ]) { const data = ready(); mutate(data); assert.equal(assessCapacityReadiness(data, now).allowed, false); }
  assert.equal(assessCapacityReadiness(ready(), now).allowed, true);
});

test('CLI produces a local plan without a token and refuses to overwrite existing evidence', () => {
  const directory = mkdtempSync(join(tmpdir(), 'ims-acceptance-plan-'));
  try {
    const input = join(directory, 'config.json'), output = join(directory, 'plan.json');
    writeFileSync(input, JSON.stringify({ ...config, ignoredAccessToken: 'must-not-be-copied' }));
    const child = spawnSync(process.execPath, ['scripts/prepare-dev-product-acceptance.mjs', input, output], { encoding: 'utf8', env: {} });
    assert.equal(child.status, 0, child.stderr);
    const result = readFileSync(output, 'utf8');
    assert.equal(JSON.parse(result).schoolId, 1);
    assert.ok(!result.includes('must-not-be-copied'));
    const repeat = spawnSync(process.execPath, ['scripts/prepare-dev-product-acceptance.mjs', input, output], { encoding: 'utf8' });
    assert.notEqual(repeat.status, 0);
    assert.equal(readFileSync(output, 'utf8'), result);
  } finally {
    assert.equal(dirname(resolve(directory)), resolve(tmpdir()));
    assert.ok(basename(directory).startsWith('ims-acceptance-plan-'));
    rmSync(directory, { recursive: true });
  }
});
