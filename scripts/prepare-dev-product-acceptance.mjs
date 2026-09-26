#!/usr/bin/env node
// Preparation only: this module deliberately has no HTTP client or cloud command execution.
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const DEV_GATEWAY = 'https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app';
const syntheticRun = /^ACPT-[0-9]{8}-[a-z0-9]{6,20}$/;
const encoded = encodeURIComponent;

export function assessCapacityReadiness(input, now = new Date()) {
  const blockers = [];
  const freshHours = (value) => {
    if (typeof value !== 'string' || !/(Z|[+-]\d\d:\d\d)$/.test(value)) return Infinity;
    return (now.getTime() - Date.parse(value)) / 3_600_000;
  };
  const isFresh = (value, maximum) => Number.isFinite(freshHours(value)) && freshHours(value) >= -0.1 && freshHours(value) <= maximum;
  if (input.project !== 'custoking-dev' || input.instance !== 'custoking-db-dev') blockers.push('Dev project and database identity must match exactly.');
  if (input.billing?.scopeProject !== 'custoking-dev' || input.billing?.currency !== 'INR' || input.billing?.source !== 'detailed-billing-export') blockers.push('Require invoice-grade INR billing rows filtered to custoking-dev.');
  for (const field of ['latestExportAt', 'latestUsageAt']) {
    const age = freshHours(input.billing?.[field]);
    if (!Number.isFinite(age) || age < -0.1 || age > 24) blockers.push(`Billing ${field} must be no more than 24 hours old, with an explicit UTC offset.`);
  }
  const { grossMonthInr, budgetInr, estimatedRunInr } = input.billing ?? {};
  if (![grossMonthInr, budgetInr, estimatedRunInr].every(v => typeof v === 'number' && Number.isFinite(v) && v >= 0)
    || budgetInr <= 0 || estimatedRunInr <= 0) blockers.push('Known gross spend, positive budget, and positive run estimate are required; credits do not substitute for gross cost.');
  else if (grossMonthInr + estimatedRunInr > budgetInr * 0.8) blockers.push('Projected gross spend exceeds the unchanged 80% budget guard.');
  const { monthGib, estimatedRunGib, observedAt } = input.logging ?? {};
  if (![monthGib, estimatedRunGib].every(v => typeof v === 'number' && Number.isFinite(v) && v >= 0)
    || estimatedRunGib <= 0 || monthGib + estimatedRunGib > 40) blockers.push('Known Logging usage plus a positive run estimate must remain below 40 GiB (80% of 50 GiB).');
  if (!isFresh(observedAt, 2)) blockers.push('Logging ingestion evidence must be observed within two hours.');
  if (input.database?.state !== 'RUNNABLE') blockers.push('Cloud SQL must be RUNNABLE.');
  if (input.database?.cpuRatio >= 0.8 || input.database?.memoryUsagePercent >= 90 || input.database?.connections >= 140) blockers.push('Database already breaches the CPU, memory, or connection stop threshold.');
  if (!['cpuRatio', 'memoryUsagePercent', 'connections'].every(k => typeof input.database?.[k] === 'number' && Number.isFinite(input.database[k]) && input.database[k] >= 0)) blockers.push('All three live database metrics are required.');
  if (!isFresh(input.database?.observedAt, 5 / 60)) blockers.push('Database metrics must be no more than five minutes old.');
  if (input.fixture?.schoolId < 900000000 || !Number.isSafeInteger(input.fixture?.schoolId) || input.fixture?.verifiedSynthetic !== true) blockers.push('Capacity workload needs a verified reserved synthetic fixture; ordinary dev schools are not load fixtures.');
  return { allowed: blockers.length === 0, blockers, budgetOverrideSupported: false, estimatedRunInr: estimatedRunInr ?? null };
}

export function buildAcceptancePlan(config) {
  if (config.project !== 'custoking-dev' || config.baseUrl !== DEV_GATEWAY) throw new Error('Plan target must be the exact custoking-dev gateway.');
  if (!Number.isSafeInteger(config.schoolId) || config.schoolId <= 0) throw new Error('A positive selected dev schoolId is required.');
  if (!syntheticRun.test(config.runId ?? '')) throw new Error('runId must be ACPT-YYYYMMDD- followed by 6–20 lowercase letters/digits.');
  for (const key of ['classId', 'feeBandId', 'feeSchedule', 'academicYearId', 'attendanceDate']) {
    if (typeof config[key] !== 'string' || !config[key].trim()) throw new Error(`${key} is required; choose scoped reference data during authenticated read-only preflight.`);
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(config.attendanceDate) || !Number.isFinite(Date.parse(config.attendanceDate))
    || new Date(config.attendanceDate).toISOString().slice(0, 10) !== config.attendanceDate) throw new Error('attendanceDate must be a real ISO date.');
  if (!['Annual', 'Half-yearly', 'Quarterly', 'Monthly'].includes(config.feeSchedule)) throw new Error('feeSchedule must match an active published-band schedule.');
  const { schoolId, runId, classId, feeBandId, academicYearId, attendanceDate } = config;
  const marker = `${runId}-01`;
  const qs = `schoolId=${schoolId}`;
  const request = (id, method, path, body, expect, safety = []) => ({ id, method, path, ...(body === undefined ? {} : { body }), expect, safety });
  const student = { admissionNumber: marker, fullName: `Synthetic Acceptance ${runId}`, schoolId, classId,
    sectionName: runId, admissionDate: attendanceDate, gender: 'Unspecified' };
  const payment = { schoolId, studentId: '${student.id}', assignmentId: '${assignment.id}', academicYearId,
    amount: 100, mode: 'CASH', notes: `SYNTHETIC ACCEPTANCE ONLY ${runId}`, paidAt: '${fixedStartedAtUtc}', idempotencyKey: `${runId}:fee:1` };
  const procurement = { schoolId, title: `Synthetic acceptance ${runId}`, category: 'Other', urgency: 'LOW', estimatedBudget: 1,
    description: `TEST ONLY - DO NOT FULFIL ${runId}`, idempotencyKey: `${runId}:request:1` };
  const quotation = { vendorName: `Synthetic vendor ${runId}`, amount: 1, notes: 'TEST ONLY - NO ORDER OR VENDOR CONTACT', idempotencyKey: `${runId}:quote:1` };
  const register = { schoolId, classId, sectionId: '${student.sectionId}', date: attendanceDate,
    records: [{ studentId: '${student.id}', status: 'PRESENT', remarks: `Synthetic acceptance ${runId}` }] };
  return {
    schemaVersion: 1, mode: 'PLAN_ONLY_NO_NETWORK', project: config.project, baseUrl: config.baseUrl, schoolId, runId,
    executableInstructions: 'This command prepares the ordered HTTP plan; it does not send it. Resolve ${...} only from checked prior responses. The deployment operator executes after the release is accepted for dev.',
    tokenSource: 'IMS_DEV_ACCEPTANCE_TOKEN environment variable; never put a token in this plan or evidence',
    principalPreflight: { source: 'The successful POST /api/v1/auth/login response retained only in process with its access token.',
      expect: ['Authenticated user school/branch equals schoolId', 'School-scoped ADMIN role and necessary permissions; not a superadmin bypass'],
      safety: 'Do not infer authorization from an unverified decoded JWT, persist credentials/principal details, or call the internal introspection endpoint. The scoped school read below must succeed through the gateway before any write.' },
    role: 'A school-scoped ADMIN actor with school:read, student:create/read, attendance:manage/read, fee:assign/collect/read, firefighting:create/update/read, plan:manage/read and required enabled modules.',
    limits: { concurrency: 1, maxHttpRequests: 45, maxDurationSeconds: 600, requestTimeoutSeconds: 30, maxCreatedStudents: 1, maxPayments: 1,
      paymentAmountPaise: 100, maxProcurementRequests: 1, maxQuotations: 1, maxAnnualPlanItems: 1 },
    stopImmediately: ['Any auth/school/module mismatch or non-synthetic record in a write scope', 'Any unexpected HTTP status, timeout or malformed response; keep the journal and reconcile before a retry', 'Any 429, 5xx, external-delivery attempt, duplicate record, changed-payload acceptance, or total-duration/request limit breach'],
    journal: { beforeFirstWrite: true, contents: 'Exact synthetic payloads, fixed timestamp, original keys and confirmed identifiers; no credentials, provider secrets, or existing student details.',
      noOverwrite: true, resumeRule: 'Reuse the same runId, journal and creation keys. Do not restart with new keys after uncertainty.' },
    preflight: [
      request('school-scope', 'GET', `/api/v1/schools/${schoolId}`, undefined, ['Returned school id equals schoolId; authorized gateway read succeeds for the checked login principal.']),
      request('fee-band', 'GET', `/api/v1/fees/structure?${qs}&academicYearId=${encoded(academicYearId)}`, undefined,
        [`Only READ existing band ${feeBandId}; require PUBLISHED, matching school/year/class and >=100 paise balance after assignment.`]),
      request('admission-absent', 'GET', `/api/v1/students?${qs}&q=${encoded(marker)}&page=0&size=20`, undefined,
        ['For a fresh journal: no exact admissionNumber match. For resume: exactly one matching run-owned student, otherwise stop.']),
      request('annual-inventory', 'GET', `/api/v1/catalog/annual-plan/review?${qs}`, undefined,
        ['Only run-owned items may be included in a confirmation; empty is allowed before adding the item. Existing ordinary plan items BLOCK the annual-plan phase.']),
      request('document-capability', 'GET', '/api/v1/ff/quotation-documents/capabilities', undefined,
        ['Report actual available/canUpload; unavailable is a documented dependency, not a passed upload test.'])
    ],
    phases: [
      { name: 'admission', steps: [
        request('student-create', 'POST', '/api/v1/students', student, ['Save id, schoolId, classId, sectionId and admissionNumber from response.'],
          ['No phone, email, guardian contact, photo, existing studentId, or existing sectionId is supplied. A unique test section is created by sectionName.']),
        request('student-reconcile', 'GET', `/api/v1/students?${qs}&q=${encoded(marker)}&page=0&size=20`, undefined,
          ['Exactly one exact admissionNumber and fullName match belongs to selected school.'], ['After a lost create response, reconcile before any repeat POST; duplicate-admission response is not a new student.']),
        request('student-details', 'GET', '/api/v1/students/${student.id}', undefined, ['Exact marker, school and test section match.'])
      ] },
      { name: 'attendance', steps: [
        request('section-baseline', 'GET', `/api/v1/attendance/section-register?${qs}&classId=${encoded(classId)}&sectionId=\${student.sectionId}&date=${attendanceDate}`, undefined,
          ['Exactly one enrolled student, matching the synthetic admission, and section name equals runId.', 'No locked register or preexisting non-test attendance.']),
        request('attendance-save', 'PUT', '/api/v1/attendance/section-register', register, ['Exactly one PRESENT record and matching student/section/date.']),
        request('attendance-replay', 'PUT', '/api/v1/attendance/section-register', register, ['Same daily identifier; one record remains, no duplicate student/date row.'], ['Do not call submit-day or absentee notification.'])
      ] },
      { name: 'fee-replay', steps: [
        request('fee-assign', 'POST', '/api/v1/fees/assignments', { studentId: '${student.id}', bandId: feeBandId, schedule: config.feeSchedule, academicYearId },
          ['Record assignment id/year and outstanding amount; it must belong only to the new synthetic student.'], ['Never revise/publish a shared fee band or use an existing student.']),
        request('fee-collect', 'POST', '/api/v1/fees/payments', payment, ['Save payment id and receiptNumber; amount equals100 paise.']),
        request('fee-replay', 'POST', '/api/v1/fees/payments', payment, ['Same payment id/receiptNumber and unchanged paid total on repeated original payload.']),
        request('fee-conflict', 'POST', '/api/v1/fees/payments', { ...payment, amount: 101 }, ['HTTP409; no second payment.']),
        request('fee-ledger', 'GET', '/api/v1/fees/payments?studentId=${student.id}&limit=100', undefined, ['Exactly one run-owned payment; receipt and assignment totals reconcile.'])
      ] },
      { name: 'procurement-replay', steps: [
        request('request-create', 'POST', '/api/v1/ff/requests', procurement, ['Save request code; status DRAFT; school matches.']),
        request('request-replay', 'POST', '/api/v1/ff/requests', procurement, ['Same request code.']),
        request('request-conflict', 'POST', '/api/v1/ff/requests', { ...procurement, title: `${procurement.title} CHANGED` }, ['HTTP409; original unchanged.']),
        request('quote-create', 'POST', '/api/v1/ff/requests/${request.code}/quotations', quotation, ['Save quotation id and matching requestId.']),
        request('quote-replay', 'POST', '/api/v1/ff/requests/${request.code}/quotations', quotation, ['Same quotation id; one quotation on request.']),
        request('quote-conflict', 'POST', '/api/v1/ff/requests/${request.code}/quotations', { ...quotation, amount: 2 }, ['HTTP409; original unchanged.']),
        request('request-submit', 'POST', '/api/v1/ff/requests/${request.code}/submit', undefined, ['Matching request code and AWAITING_BURSAR.'], ['Stop at submission; no approval, payment, fulfillment, or vendor contact.']),
        request('request-submit-replay', 'POST', '/api/v1/ff/requests/${request.code}/submit', undefined, ['Same code/status; no second transition.']),
        request('request-reconcile', 'GET', '/api/v1/ff/requests/${request.code}', undefined, ['One quotation and expected submitted status; no fabricated notification success.'])
      ] },
      { name: 'annual-plan', gate: 'Before ANY item write, require annual-inventory.items empty or every item id equal this journal-owned item. Otherwise BLOCK this phase and use a dedicated clean synthetic school.', steps: [
        request('plan-item', 'POST', `/api/v1/catalog/annual-plan/items?${qs}`, { id: `${runId}:plan:1`, category: 'STATIONERY', termName: 'Term 1', description: `TEST ONLY ${runId}`, quantity: '1', estimatedAmount: 1, status: 'PLANNED' }, ['Exact caller-supplied id is preserved.']),
        request('plan-review', 'GET', `/api/v1/catalog/annual-plan/review?${qs}`, undefined, ['Exactly one run-owned item and nonempty fingerprint.']),
        request('plan-confirm', 'POST', `/api/v1/catalog/annual-plan/confirm?${qs}`, { fingerprint: '${review.fingerprint}' }, ['confirmed=true; itemCount1; save confirmation id and notificationStatus NOT_SENT.']),
        request('plan-confirm-replay', 'POST', `/api/v1/catalog/annual-plan/confirm?${qs}`, { fingerprint: '${review.fingerprint}' }, ['Same confirmation id/revision, no additional confirmation.']),
        request('plan-confirm-conflict', 'POST', `/api/v1/catalog/annual-plan/confirm?${qs}`, { fingerprint: '0'.repeat(64) }, ['HTTP409; no false confirmation.'])
      ] }
    ],
    evidence: 'Only run label, confirmed synthetic identifiers, response status, timing, count/total deltas and invariant results. A blocked phase remains BLOCKED; do not label the whole run passed.',
    cleanup: 'Retain the journal for a separate owner-reviewed synthetic-only cleanup. No existing student, fee band, school plan or shared class may be deleted. There is no supported API to safely undo every confirmation/payment, so no automatic delete or SQL cleanup is generated.',
    capacity: { blockedUntilFreshCostAndDeploymentAccepted: true, proposedProbe: { kind: 'read-only diagnostic, not fleet certification', verifiedSyntheticSchoolOnly: true,
      concurrency: 1, phases: [{ durationSeconds: 60, targetRps: 1 }, { durationSeconds: 60, targetRps: 3 }], maximumRequests: 240,
      paths: ['students page size20', 'attendance daily summary', 'fees structure', 'procurement list limit20'],
      stop: { unexpected4xxOr5xx: 1, requestTimeoutSeconds: 10, consecutiveResponsesOver5Seconds: 2, cpuRatio: 0.8, memoryUsagePercent: 90, connectionsAcrossAllDatabases: 140, consecutiveMetricBreaches: 2, consecutiveMissingOrStaleMonitoringSamples: 2 },
      requires: 'A guard-capable runner must enforce these runtime stops; this plan does not start a runner. Do not use existing35-minute/300k fixture profile on db-f1-micro.' } }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [configPath, outputPath] = process.argv.slice(2);
  if (!configPath) throw new Error('Usage: node scripts/prepare-dev-product-acceptance.mjs <reviewed-config.json> [new-plan.json] (no network)');
  if (configPath === '--capacity') {
    if (!outputPath) throw new Error('Provide a fresh read-only capacity evidence JSON path.');
    const assessment = assessCapacityReadiness(JSON.parse(readFileSync(outputPath, 'utf8')));
    process.stdout.write(`${JSON.stringify(assessment, null, 2)}\n`);
    process.exitCode = assessment.allowed ? 0 : 2;
  } else {
    const plan = buildAcceptancePlan(JSON.parse(readFileSync(configPath, 'utf8')));
    const json = `${JSON.stringify(plan, null, 2)}\n`;
    if (outputPath) writeFileSync(outputPath, json, { flag: 'wx' });
    else process.stdout.write(json);
  }
}
