import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, openSync, closeSync, readFileSync, writeFileSync, fsyncSync, renameSync, unlinkSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

// Deliberately fixed destination: neither a URL nor a school can be supplied by the caller.
export const PROJECT = 'custoking-dev';
export const BASE = 'https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app/api/v1';
const SCHOOL = 1;
const MAX_REQUESTS = 160;
const BUSINESS_REQUESTS = 145;
const BUSINESS_DEADLINE_MS = 15 * 60_000;
export const SYNTHETIC_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');
const sha = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex');
export function check(condition, code) { if (!condition) throw new Error(code); }
const array = value => { check(Array.isArray(value), 'INVALID_LIST_RESPONSE'); return value; };
const only = rows => { check(rows.length <= 1, 'AMBIGUOUS_OWNERSHIP'); return rows[0] ?? null; };
const positive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
const enc = encodeURIComponent;
function noCredentials(value) {
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    check(!/password|accesstoken|refreshtoken|authorization|cookie|seed(sql)?|credential/i.test(key), 'CREDENTIAL_FIELD_CANNOT_BE_JOURNALED');
    noCredentials(child);
  }
}

export function parseArgs(args) {
  const result = { apply: false, runId: null, project: null, directory: 'artifacts/product-dev-release-2026-09-26/acceptance' };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--apply') result.apply = true;
    else if (args[i] === '--project') result.project = args[++i];
    else if (args[i] === '--run-id') result.runId = args[++i];
    else if (args[i] === '--directory') result.directory = args[++i];
    else throw new Error('UNKNOWN_ARGUMENT');
  }
  check(result.project === PROJECT, 'EXPLICIT_DEV_PROJECT_REQUIRED');
  check(typeof result.runId === 'string' && /^[a-z0-9][a-z0-9-]{5,23}$/.test(result.runId), 'STABLE_RUN_ID_REQUIRED_6_TO_24_LOWERCASE_CHARACTERS');
  return result;
}

export function plan(options) {
  return { project: PROJECT, gateway: BASE, schoolId: SCHOOL, runId: options.runId, apply: options.apply,
    maximumRequests: MAX_REQUESTS, businessRequestLimit: BUSINESS_REQUESTS, cleanupRequestReserve: 15,
    businessDeadlineMinutes: 15, timeoutPerRequestSeconds: 58, automaticHttpRetries: 0,
    phases: ['Verify synthetic school, configured modules, current year, and private-file capability',
      'Create one run-owned school administrator; restore only that exact administrator on resume',
      'Create one contact-free synthetic student in a new run-owned section',
      'Save PRESENT attendance twice and read back exactly one record',
      'Create a run-owned fee band only when the year has no other bands; publish a 100-paise Annual plan',
      'Assign that plan, record CASH 100 paise, replay same key, reject changed payload with 409, verify one payment',
      'Create/replay/conflict-test a procurement request and quotation; upload/read/remove a synthetic PNG; submit/replay',
      'Confirm annual plan only when empty or entirely this run\'s item; repeat and verify durable receipt',
      'Log out school administrator, disable it, verify disabled state, then log out superadministrator'],
    journal: resolve(options.directory, `${options.runId}.json`),
    retention: 'Synthetic business records and immutable payment/confirmation evidence remain for audit. No reminder/send API, purchase approval, vendor payment or live provider is invoked. Ordinary workflow events may be processed by the dev logging provider.',
    resume: 'Use the same run-id and journal. Uncertain non-idempotent writes must reconcile to an exact owned record; absence stops the run. Never delete a journal to retry.',
    credentials: 'Bootstrap secrets and random temporary password exist only in process memory; no tokens, passwords, raw API responses, or existing student data are logged.' };
}

export class Journal {
  constructor(path, runId) {
    this.path = path;
    if (existsSync(path)) this.data = JSON.parse(readFileSync(path, 'utf8'));
    else this.data = { version: 1, project: PROJECT, schoolId: SCHOOL, runId, createdAt: new Date().toISOString(),
      date: new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()),
      annualItemId: randomUUID(), operations: {}, results: {}, checks: [], attempts: 0 };
    check(this.data.version === 1 && this.data.project === PROJECT && this.data.schoolId === SCHOOL && this.data.runId === runId, 'JOURNAL_SCOPE_MISMATCH');
  }
  save() {
    mkdirSync(dirname(this.path), { recursive: true });
    const temporary = `${this.path}.tmp`;
    const fd = openSync(temporary, 'w', 0o600);
    try { writeFileSync(fd, `${JSON.stringify(this.data, null, 2)}\n`); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(temporary, this.path);
  }
  result(name, value) { this.data.results[name] = value; this.save(); return value; }
  async mutate(name, { intent, send, reconcile, replaySafe = false, prove }) {
    noCredentials(intent);
    const fingerprint = sha(intent);
    const prior = this.data.operations[name];
    if (prior) check(prior.fingerprint === fingerprint, 'JOURNAL_INTENT_CHANGED');
    if (prior?.state === 'confirmed') return prior.proof;
    if (reconcile) {
      const recovered = await reconcile();
      if (recovered !== null) {
        noCredentials(recovered);
        this.data.operations[name] = { intent, fingerprint, state: 'confirmed', reconciled: true, proof: recovered };
        this.save(); return recovered;
      }
    }
    check(!prior || replaySafe, 'UNCERTAIN_NON_IDEMPOTENT_WRITE_REQUIRES_RECONCILIATION');
    // Durable intent is flushed BEFORE the network request; never store actual credential bodies.
    this.data.operations[name] = { intent, fingerprint, state: 'uncertain', attemptedAt: new Date().toISOString() };
    this.save();
    const response = await send();
    this.data.operations[name].httpStatus = response.status;
    this.save();
    const proof = await prove(response);
    check(proof && typeof proof === 'object', 'MISSING_PERSISTENCE_PROOF');
    noCredentials(proof);
    this.data.operations[name] = { ...this.data.operations[name], state: 'confirmed', proof };
    this.save(); return proof;
  }
}

export class Api {
  constructor(journal, label) { this.journal = journal; this.label = label; this.token = ''; this.cookies = ''; this.loggedIn = false; }
  async request(method, path, body, { binary = false, form = false } = {}) {
    check(path.startsWith('/') && !path.startsWith('//') && !path.includes('://'), 'INVALID_API_PATH');
    check(this.journal.cleanupMode || Date.now() < this.journal.deadline, 'BUSINESS_DEADLINE_EXCEEDED');
    check(this.journal.requestCount < (this.journal.cleanupMode ? MAX_REQUESTS : BUSINESS_REQUESTS), 'REQUEST_BUDGET_EXCEEDED');
    this.journal.requestCount++;
    const headers = {};
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (this.cookies) headers.Cookie = this.cookies;
    if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await this.journal.fetchImpl(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : form ? body : JSON.stringify(body),
        redirect: 'error', signal: AbortSignal.timeout(58_000) });
      if (path === '/auth/login') {
        this.cookies = response.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
        this.loggedIn = response.ok; // Cleanup even when the principal response is unexpected.
      }
      const buffer = Buffer.from(await response.arrayBuffer());
      check(buffer.length <= 6 * 1024 * 1024, 'RESPONSE_TOO_LARGE');
      const data = binary ? buffer : buffer.length ? JSON.parse(buffer.toString('utf8')) : null;
      this.journal.data.checks.push({ actor: this.label, method, path, status: response.status, at: new Date().toISOString() });
      this.journal.save();
      return { status: response.status, data, headers: response.headers };
    } catch {
      this.journal.data.checks.push({ actor: this.label, method, path, status: response?.status ?? 0, result: 'UNCONFIRMED', at: new Date().toISOString() });
      this.journal.save(); throw new Error('HTTP_RESULT_UNCONFIRMED');
    }
  }
  async get(path) { const r = await this.request('GET', path); check(r.status === 200, `READ_HTTP_${r.status}`); return r.data; }
  async login(email, password, role) {
    const r = await this.request('POST', '/auth/login', { email, password });
    check(r.status === 200 && r.data?.role === role && r.data.accessToken, 'UNEXPECTED_AUTHENTICATED_PRINCIPAL');
    this.token = r.data.accessToken;
    return r.data;
  }
  async logout() {
    if (!this.loggedIn) return true;
    const r = await this.request('POST', '/auth/logout');
    check(r.status === 204 || r.status === 200, 'LOGOUT_UNCONFIRMED');
    this.token = ''; this.cookies = ''; this.loggedIn = false; return true;
  }
}

function bootstrapCredentials() {
  function secret(name) {
    // All command text is fixed except this allowlisted secret name. Secret output is captured only.
    check(['seed-superadmin-sql', 'superadmin-password-dev'].includes(name), 'SECRET_NOT_ALLOWED');
    try {
      if (process.platform === 'win32') return execFileSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c',
        `gcloud.cmd secrets versions access latest --secret=${name} --project=custoking-dev`], { encoding: 'utf8', windowsHide: true, timeout: 58_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
      return execFileSync('gcloud', ['secrets', 'versions', 'access', 'latest', `--secret=${name}`, '--project=custoking-dev'],
        { encoding: 'utf8', timeout: 58_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch { throw new Error('DEV_BOOTSTRAP_SECRET_UNAVAILABLE'); }
  }
  let seed = secret('seed-superadmin-sql');
  const emails = [...new Set((seed.match(/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/g) || []).map(e => e.toLowerCase()))];
  seed = null;
  check(emails.length === 1, 'AMBIGUOUS_BOOTSTRAP_EMAIL');
  return { email: emails[0], password: secret('superadmin-password-dev') };
}
const ok = r => check(r.status >= 200 && r.status < 300, `WRITE_HTTP_${r.status}`);
const verifyConflict = r => { check(r.status === 409, `EXPECTED_409_GOT_${r.status}`); return { rejected: true, httpStatus: 409 }; };

// Publication's first response wraps the band; the server's already-published replay returns it
// directly. Both must prove exactly the reviewed synthetic plan, not merely a PUBLISHED status.
export function feePublicationSnapshot(band) {
  check(band && typeof band === 'object' && Array.isArray(band.items) && Array.isArray(band.installments)
    && Array.isArray(band.activeSchedules), 'FEE_PUBLICATION_SNAPSHOT_MISSING');
  return { id: band.id, schoolId: band.schoolId, academicYearId: band.academicYearId, name: band.name,
    classFrom: band.classFrom, classTo: band.classTo, discount: band.discount, activeSchedules: band.activeSchedules,
    annualTotal: band.annualTotal, gracePeriodDays: band.gracePeriodDays, lateFeeType: band.lateFeeType,
    lateFeeAmount: band.lateFeeAmount, lateFeeIntervalDays: band.lateFeeIntervalDays,
    items: band.items.map(i => ({ id: i.id, name: i.name, frequency: i.frequency, amount: i.amount, optional: i.optional })),
    installments: band.installments.map(i => ({ label: i.label, dueDate: i.dueDate, sharePercent: i.sharePercent })) };
}

export function proveFeePublication(response, expectedSnapshot) {
  ok(response);
  const band = response.data?.band ?? response.data;
  check(band?.status === 'PUBLISHED', 'FEE_PUBLISH_NOT_CONFIRMED');
  const fingerprint = sha(expectedSnapshot);
  check(sha(feePublicationSnapshot(band)) === fingerprint, 'FEE_PUBLICATION_OWNERSHIP_OR_CONTENT_CHANGED');
  return { id: band.id, status: 'PUBLISHED', fingerprint };
}

export async function execute(options, { credentialsReader = bootstrapCredentials, fetchImpl = fetch } = {}) {
  check(options.apply && options.project === PROJECT, 'APPLY_DEV_REQUIRED');
  const directory = resolve(options.directory);
  mkdirSync(directory, { recursive: true });
  const path = resolve(directory, `${options.runId}.json`);
  let lock;
  try { lock = openSync(`${path}.lock`, 'wx', 0o600); } catch { throw new Error('RUN_LOCK_EXISTS_DO_NOT_START_CONCURRENT_OR_DISCARD_JOURNAL'); }
  const j = new Journal(path, options.runId);
  j.requestCount = 0;
  j.deadline = Date.now() + BUSINESS_DEADLINE_MS;
  j.cleanupMode = false;
  j.fetchImpl = fetchImpl;
  const root = new Api(j, 'SUPERADMIN'); const school = new Api(j, 'ADMIN');
  const run = options.runId;
  const adminEmail = `acceptance-${run}@synthetic.invalid`;
  const adminName = `Synthetic acceptance ${run}`;
  let administratorId = null;
  let failed = null;
  const userOwned = u => u && u.email === adminEmail && u.fullName === adminName && u.role === 'ADMIN' && Number(u.branchId) === SCHOOL;
  const findAdmin = async () => {
    const users = array(await root.get('/users?role=ADMIN&branchId=1&limit=500'));
    check(users.length < 500, 'ADMIN_LOOKUP_TRUNCATED');
    const result = only(users.filter(u => u.email === adminEmail));
    if (result) check(userOwned(result), 'ADMIN_OWNERSHIP_MISMATCH');
    return result;
  };
  const mutation = (name, api, method, endpoint, body, prove, extra = {}) => j.mutate(name, {
    intent: { method, endpoint, body }, send: () => api.request(method, endpoint, body), prove, ...extra });
  try {
    if (j.data.attempts > 0) {
      j.data.attemptHistory ??= [];
      j.data.attemptHistory.push({ attempt: j.data.attempts, failure: j.data.failure ?? null, cleanup: j.data.cleanup ?? null, finishedAt: j.data.lastFinishedAt ?? null });
    }
    j.data.attempts++; j.data.completed = false; j.data.failure = null; delete j.data.cleanupRequired; j.save();
    let credentials = credentialsReader();
    await root.login(credentials.email, credentials.password, 'SUPERADMIN'); credentials = null;
    const reference = await root.get('/schools/1');
    check(reference.id === SCHOOL && reference.active === true && reference.name === 'Local Demo School', 'SYNTHETIC_SCHOOL_GUARD_FAILED');
    const today = j.data.date;
    const startMonth = Number(reference.academicYearStartMonth || 4);
    const date = new Date(`${today}T12:00:00Z`);
    const startYear = date.getUTCFullYear() - (date.getUTCMonth() + 1 < startMonth ? 1 : 0);
    const year = `ay_${startYear}_${String(startYear + 1).slice(-2)}`;
    check(array(await root.get('/academic-years')).some(y => y.id === year), 'CURRENT_YEAR_NOT_PROVISIONED');
    const modules = array(await root.get('/schools/1/modules/active')).map(m => m.moduleCode || m.code);
    check(['STUDENTS', 'ATTENDANCE', 'FEES', 'FIREFIGHTING', 'ORDERS'].every(m => modules.includes(m)), 'REQUIRED_MODULE_NOT_ENABLED');
    const capability = await root.get('/ff/quotation-documents/capabilities');
    check(capability.available === true && capability.canUpload === true, 'PRIVATE_QUOTATION_STORAGE_NOT_READY');
    // Canonical deployed route must exist before creating the temporary account.
    const initialAnnual = await root.get('/catalog/annual-plan/review?schoolId=1');
    check(initialAnnual.schoolId === SCHOOL && initialAnnual.academicYearId === year && Array.isArray(initialAnnual.items), 'ANNUAL_REVIEW_SCOPE_MISMATCH');
    const initialFees = await root.get(`/fees/structure?schoolId=1&academicYearId=${year}`);
    check(initialFees.academicYearId === year && array(initialFees.bands).every(b => b.name === `Synthetic acceptance ${run}`), 'EXISTING_FEE_BANDS_NOT_RUN_OWNED');
    j.result('preflight', { schoolId: SCHOOL, syntheticSchoolVerified: true, academicYearId: year, privateStorageAvailable: true });

    let temporaryPassword = `Aa7!${randomBytes(30).toString('base64url')}`;
    const admin = await j.mutate('admin-create', {
      intent: { endpoint: '/schools/1/admin', owner: run, role: 'ADMIN' },
      reconcile: async () => { const u = await findAdmin(); return u ? { userId: u.id } : null; },
      send: () => root.request('POST', '/schools/1/admin', { fullName: adminName, email: adminEmail, temporaryPassword }),
      prove: r => { ok(r); check(positive(r.data?.userId) && Number(r.data.branchId) === SCHOOL && r.data.email === adminEmail, 'ADMIN_CREATE_PROOF_INVALID'); return { userId: r.data.userId }; }
    });
    administratorId = Number(admin.userId);
    const owned = await root.get(`/users/${administratorId}`); check(userOwned(owned), 'ADMIN_OWNERSHIP_MISMATCH');
    // Password is never persisted. On resume rotate only the exact verified run-owned account.
    if (j.data.attempts > 1 || j.data.operations['admin-create'].reconciled) {
      await mutation(`admin-password-${j.data.attempts}`, root, 'POST', `/users/${administratorId}/password-reset`, { password: temporaryPassword },
        r => { ok(r); return { reset: true }; }, { intent: { operation: 'run-owned-admin-password', userId: administratorId, attempt: j.data.attempts }, replaySafe: true });
    }
    if (!owned.active) await mutation(`admin-enable-${j.data.attempts}`, root, 'POST', `/users/${administratorId}/enable`, undefined,
      r => { ok(r); return { enabled: true }; }, { replaySafe: true });
    const principal = await school.login(adminEmail, temporaryPassword, 'ADMIN'); temporaryPassword = null;
    check(Number(principal.branchId) === SCHOOL && Number(principal.userId) === administratorId, 'SCHOOL_ACTOR_SCOPE_MISMATCH');
    check(['student:create', 'student:read', 'attendance:manage', 'fee_structure:manage', 'fee:assign', 'fee:collect', 'firefighting:create', 'firefighting:update', 'plan:manage']
      .every(p => principal.permissions?.includes(p)), 'SCHOOL_ACTOR_PERMISSION_MISSING');
    check((await school.get('/schools/1')).id === SCHOOL, 'SCHOOL_ACTOR_REFERENCE_SCOPE_MISMATCH');

    const classId = '1';
    check(array(await school.get('/classes?schoolId=1')).some(c => c.id === classId), 'CLASS_ONE_NOT_AVAILABLE');
    const sectionName = `QA-${run}`.toUpperCase();
    const admission = `QA-${run}`;
    const studentName = `Synthetic Student ${run}`;
    const studentBody = { schoolId: SCHOOL, admissionNumber: admission, fullName: studentName, classId, sectionName, rollNo: '1', admissionDate: today };
    const verifyStudent = s => {
      check(positive(s?.id) && Number(s.schoolId) === SCHOOL && s.admissionNumber === admission && s.fullName === studentName && s.classId === classId && s.sectionName === sectionName, 'STUDENT_OWNERSHIP_MISMATCH');
      check(!s.fatherContact && !s.phone && !s.motherContact && !s.photoUrl, 'SYNTHETIC_STUDENT_HAS_CONTACT_OR_PHOTO');
      return { id: s.id, sectionId: s.sectionId };
    };
    const student = await mutation('student-create', school, 'POST', '/students', studentBody, r => { ok(r); return verifyStudent(r.data); }, {
      reconcile: async () => {
        const list = await school.get(`/students?schoolId=1&q=${enc(admission)}&page=0&size=20`);
        const rows = array(list.items); check(rows.length < 20, 'STUDENT_LOOKUP_TRUNCATED');
        const found = only(rows.filter(s => s.admissionNumber === admission || s.admissionNo === admission));
        return found ? verifyStudent(await school.get(`/students/${found.id}/workspace`)) : null;
      }
    });
    verifyStudent(await school.get(`/students/${student.id}/workspace`));
    const section = await school.get(`/students/roster?schoolId=1&classId=1&sectionId=${enc(student.sectionId)}&limit=5`);
    check(array(section).length === 1 && Number(section[0].id) === Number(student.id), 'SECTION_IS_NOT_EXCLUSIVELY_RUN_OWNED');
    const attendanceBody = { schoolId: SCHOOL, classId, sectionId: student.sectionId, date: today, records: [{ studentId: student.id, status: 'PRESENT', remarks: `Synthetic acceptance ${run}` }] };
    const attendanceProof = r => { ok(r); check(r.data?.students?.length === 1 && Number(r.data.students[0].studentId) === Number(student.id) && r.data.students[0].status === 'PRESENT' && r.data.presentCount === 1, 'ATTENDANCE_NOT_PERSISTED'); return { studentId: student.id, presentCount: 1 }; };
    for (const name of ['attendance-save', 'attendance-repeat']) await mutation(name, school, 'PUT', '/attendance/section-register', attendanceBody, attendanceProof, { replaySafe: true });
    attendanceProof({ status: 200, data: await school.get(`/attendance/section-register?schoolId=1&date=${today}&classId=1&sectionId=${enc(student.sectionId)}`) });
    const records = array(await school.get(`/attendance/records?schoolId=1&studentId=${student.id}&date=${today}&limit=5`));
    check(records.length === 1 && records[0].status === 'PRESENT', 'ATTENDANCE_REPLAY_DUPLICATED');
    j.result('attendance', { studentId: student.id, date: today, persistedRecordCount: 1, repeated: true });

    const bandName = `Synthetic acceptance ${run}`;
    const structure = () => school.get(`/fees/structure?schoolId=1&academicYearId=${year}`);
    const ownedBand = async () => {
      const response = await structure(); check(response.academicYearId === year, 'FEE_YEAR_MISMATCH');
      const bands = array(response.bands);
      check(bands.every(b => b.name === bandName), 'EXISTING_FEE_BANDS_NOT_RUN_OWNED');
      return only(bands);
    };
    const bandProof = b => { check(b?.id && b.name === bandName && Number(b.schoolId) === SCHOOL && b.academicYearId === year && b.classFrom === 1 && b.classTo === 1, 'FEE_BAND_OWNERSHIP_MISMATCH'); return { id: b.id }; };
    const band = await mutation('fee-band-create', school, 'POST', '/fees/bands', { name: bandName, classFrom: 1, classTo: 1, schedules: ['Annual'], discount: 0, schoolId: SCHOOL, lateFeeType: 'NONE' },
      r => { ok(r); return bandProof(r.data); }, { reconcile: async () => { const b = await ownedBand(); return b ? bandProof(b) : null; } });
    const itemName = `Synthetic fee ${run}`;
    const item = await mutation('fee-item-create', school, 'POST', '/fees/items', { bandId: band.id, name: itemName, frequency: 'Annual', amount: 1, optional: false }, r => {
      ok(r); const row = only(array(r.data?.items).filter(i => i.name === itemName)); check(row?.amount === 100, 'FEE_ITEM_PAISA_MISMATCH'); return { id: row.id, amountPaise: 100 };
    }, { reconcile: async () => {
      const b = await ownedBand(); check(b?.id === band.id, 'FEE_BAND_MISSING');
      check(array(b.items).every(i => i.name === itemName), 'FEE_ITEMS_NOT_RUN_OWNED');
      const row = only(b.items); if (!row) return null; check(row.amount === 100, 'FEE_ITEM_PAISA_MISMATCH'); return { id: row.id, amountPaise: 100 };
    } });
    const installments = [{ label: `Synthetic ${run}`, dueDate: today, sharePercent: 100 }];
    await mutation('fee-installments', school, 'PUT', `/fees/bands/${band.id}/installments`, { installments }, r => { ok(r); check(r.data?.installments?.length === 1 && r.data.installments[0].sharePercent === 100, 'INSTALLMENTS_NOT_SAVED'); return { count: 1, sharePercent: 100 }; }, {
      replaySafe: true, reconcile: async () => { const b = await ownedBand(); const rows = array(b?.installments); return rows.length === 1 && rows[0].label === installments[0].label && rows[0].dueDate === today && rows[0].sharePercent === 100 ? { count: 1, sharePercent: 100 } : null; }
    });
    const publicationSnapshot = { id: band.id, schoolId: SCHOOL, academicYearId: year, name: bandName,
      classFrom: 1, classTo: 1, discount: 0, activeSchedules: ['Annual'], annualTotal: 100,
      gracePeriodDays: 0, lateFeeType: 'NONE', lateFeeAmount: 0, lateFeeIntervalDays: 0,
      items: [{ id: item.id, name: itemName, frequency: 'Annual', amount: 100, optional: false }], installments };
    const publicationFingerprint = sha(publicationSnapshot);
    const publishPath = `/fees/bands/${band.id}/publish`;
    await mutation('fee-publish', school, 'POST', publishPath, undefined, r => proveFeePublication(r, publicationSnapshot), {
      intent: { method: 'POST', endpoint: publishPath, fingerprint: publicationFingerprint }, replaySafe: true,
      reconcile: async () => {
        const current = await ownedBand();
        check(current && sha(feePublicationSnapshot(current)) === publicationFingerprint, 'FEE_PUBLICATION_OWNERSHIP_OR_CONTENT_CHANGED');
        if (current.status === 'PUBLISHED') return proveFeePublication({ status: 200, data: current }, publicationSnapshot);
        check(current.status === 'DRAFT', 'FEE_PUBLICATION_STATE_UNEXPECTED');
        return null;
      }
    });
    proveFeePublication({ status: 200, data: await ownedBand() }, publicationSnapshot);
    const assignmentsPath = `/fees/assignments?studentId=${student.id}&academicYearId=${year}&limit=5`;
    const assignmentProof = a => { check(a?.id && a.bandId === band.id && Number(a.studentId) === Number(student.id) && a.academicYearId === year && a.netPayable === 100, 'FEE_ASSIGNMENT_MISMATCH'); return { id: a.id }; };
    const assignment = await mutation('fee-assign', school, 'POST', '/fees/assignments', { studentId: student.id, bandId: band.id, schedule: 'Annual', academicYearId: year }, r => { ok(r); return assignmentProof(r.data?.assignment); }, {
      reconcile: async () => { const a = only(array(await school.get(assignmentsPath))); return a ? assignmentProof(a) : null; }
    });
    const payment = { schoolId: SCHOOL, studentId: student.id, assignmentId: assignment.id, academicYearId: year, amount: 100, mode: 'CASH', notes: `Synthetic acceptance ${run}`, idempotencyKey: `acceptance:${run}:payment` };
    const paymentProof = r => { ok(r); check(r.data?.paymentId && r.data.receiptNumber && r.data.amount === 100 && Number(r.data.studentId) === Number(student.id)
      && r.data.assignmentId === assignment.id && Number(r.data.schoolId) === SCHOOL && r.data.academicYearId === year && r.data.mode === 'CASH'
      && Number(r.data.actorId) === administratorId, 'PAYMENT_RECEIPT_MISMATCH'); return { paymentId: r.data.paymentId, receiptNumber: r.data.receiptNumber, amountPaise: r.data.amount }; };
    const paid = await mutation('payment-create', school, 'POST', '/fees/payments', payment, paymentProof, { replaySafe: true });
    const replay = await mutation('payment-replay', school, 'POST', '/fees/payments', payment, paymentProof, { replaySafe: true });
    check(paid.paymentId === replay.paymentId && paid.receiptNumber === replay.receiptNumber, 'PAYMENT_REPLAY_CHANGED_RECEIPT');
    await mutation('payment-conflict', school, 'POST', '/fees/payments', { ...payment, amount: 101 }, verifyConflict, { replaySafe: true });
    const payments = array(await school.get(`/fees/payments?studentId=${student.id}&assignmentId=${assignment.id}&limit=5`));
    check(payments.length === 1 && payments[0].id === paid.paymentId && payments[0].amount === 100, 'PAYMENT_COUNT_OR_AMOUNT_MISMATCH');
    const finalAssignment = only(array(await school.get(assignmentsPath)));
    check(finalAssignment?.paidAmount === 100, 'ASSIGNMENT_PAID_TOTAL_MISMATCH');
    j.result('fees', { studentId: student.id, bandId: band.id, assignmentId: assignment.id, ...paid, paymentCount: 1, conflictRejected: true });

    const requestBody = { schoolId: SCHOOL, title: `Synthetic acceptance ${run}`, category: 'Other', urgency: 'LOW', requiredByDate: today, estimatedBudget: 100, description: 'Synthetic acceptance only. No purchase or vendor payment is authorized.', idempotencyKey: `acceptance:${run}:request` };
    const requestProof = r => { ok(r); check(r.data?.code && Number(r.data.schoolId) === SCHOOL && r.data.title === requestBody.title, 'REQUEST_OWNERSHIP_MISMATCH'); return { code: r.data.code }; };
    const request = await mutation('ff-create', school, 'POST', '/ff/requests', requestBody, requestProof, { replaySafe: true });
    const repeated = await mutation('ff-replay', school, 'POST', '/ff/requests', requestBody, requestProof, { replaySafe: true });
    check(request.code === repeated.code, 'REQUEST_REPLAY_CHANGED_ID');
    await mutation('ff-conflict', school, 'POST', '/ff/requests', { ...requestBody, estimatedBudget: 101 }, verifyConflict, { replaySafe: true });
    const requests = array(await school.get('/ff/requests?schoolId=1&limit=500'));
    check(requests.length < 500, 'REQUEST_COUNT_LOOKUP_TRUNCATED');
    const ownedRequests = requests.filter(r => r.title === requestBody.title);
    check(ownedRequests.length === 1 && ownedRequests[0].code === request.code, 'REQUEST_REPLAY_DUPLICATED');
    const requestPath = `/ff/requests/${enc(request.code)}`;
    const quoteBody = { vendorName: `Synthetic Vendor ${run}`, amount: 100, deliveryTimeline: 'Synthetic only', notes: 'No provider contact or order', idempotencyKey: `acceptance:${run}:quote` };
    const quoteProof = r => { ok(r); check(r.data?.id && r.data.vendorName === quoteBody.vendorName && r.data.amount === 100 && r.data.requestId === request.code, 'QUOTATION_OWNERSHIP_MISMATCH'); return { id: r.data.id }; };
    const quote = await mutation('quote-create', school, 'POST', `${requestPath}/quotations`, quoteBody, quoteProof, { replaySafe: true });
    const quoteReplay = await mutation('quote-replay', school, 'POST', `${requestPath}/quotations`, quoteBody, quoteProof, { replaySafe: true });
    check(quote.id === quoteReplay.id, 'QUOTATION_REPLAY_CHANGED_ID');
    await mutation('quote-conflict', school, 'POST', `${requestPath}/quotations`, { ...quoteBody, amount: 101 }, verifyConflict, { replaySafe: true });
    const quotes = array(await school.get(`${requestPath}/quotations`));
    check(quotes.length === 1 && quotes[0].id === quote.id, 'QUOTATION_REPLAY_DUPLICATED');
    const documentPath = `${requestPath}/quotations/${enc(quote.id)}/document`;
    // Tiny valid 1x1 PNG; no local private file, student image, or externally fetched asset.
    const png = SYNTHETIC_PNG;
    if (!j.data.operations['document-remove']?.proof) {
      const form = new FormData(); form.append('file', new Blob([png], { type: 'image/png' }), `synthetic-${run}.png`);
      await j.mutate('document-upload', { intent: { path: documentPath, sha256: sha(png), filename: `synthetic-${run}.png` },
        send: () => school.request('POST', documentPath, form, { form: true }),
        reconcile: async () => { const r = await school.request('GET', documentPath, undefined, { binary: true }); if (r.status === 404) return null; check(r.status === 200 && sha(r.data) === sha(png), 'DOCUMENT_RECONCILIATION_MISMATCH'); return { bytes: png.length, sha256: sha(png) }; },
        prove: r => { ok(r); check(r.data?.id && r.data.contentType === 'image/png' && r.data.sizeBytes === png.length, 'DOCUMENT_UPLOAD_NOT_CONFIRMED'); return { bytes: png.length, sha256: sha(png) }; } });
      const downloaded = await school.request('GET', documentPath, undefined, { binary: true });
      check(downloaded.status === 200 && sha(downloaded.data) === sha(png) && downloaded.headers.get('cache-control')?.includes('private') && downloaded.headers.get('cache-control')?.includes('no-store'), 'PRIVATE_DOCUMENT_DOWNLOAD_MISMATCH');
      const anonymous = new Api(j, 'ANONYMOUS');
      const denied = await anonymous.request('GET', documentPath, undefined, { binary: true });
      check([401, 403].includes(denied.status), 'ANONYMOUS_DOCUMENT_ACCESS_NOT_DENIED');
      j.result('privateDocument', { sha256: sha(png), bytes: png.length, authenticatedRoundTrip: true, anonymousHttpStatus: denied.status });
      await mutation('document-remove', school, 'DELETE', documentPath, undefined, r => { check(r.status === 204, 'DOCUMENT_REMOVE_NOT_CONFIRMED'); return { removed: true }; }, {
        replaySafe: true, reconcile: async () => { const r = await school.request('GET', documentPath, undefined, { binary: true }); check([200, 404].includes(r.status), 'DOCUMENT_REMOVE_STATE_UNCONFIRMED'); return r.status === 404 ? { removed: true } : null; }
      });
    }
    const removed = await school.request('GET', documentPath, undefined, { binary: true });
    check(removed.status === 404, 'REMOVED_DOCUMENT_STILL_READABLE');
    const submitProof = r => { ok(r); check(r.data?.code === request.code && r.data.status === 'AWAITING_BURSAR', 'SUBMISSION_STATE_UNEXPECTED'); return { code: request.code, status: r.data.status }; };
    const submit = await mutation('ff-submit', school, 'POST', `${requestPath}/submit`, undefined, submitProof, { replaySafe: true });
    await mutation('ff-submit-replay', school, 'POST', `${requestPath}/submit`, undefined, submitProof, { replaySafe: true });
    const finalRequest = await school.get(requestPath);
    check(finalRequest.status === submit.status && finalRequest.quotations?.length === 1, 'REQUEST_FINAL_STATE_MISMATCH');
    j.result('procurement', { requestCode: request.code, quotationId: quote.id, requestReplaySameId: true, requestCount: 1, quotationCount: 1, conflictsRejected: true, documentRoundTripVerified: true, documentRemoved: true, status: finalRequest.status });

    let review = await school.get('/catalog/annual-plan/review?schoolId=1');
    const annualDescription = `Synthetic acceptance ${run}`;
    check(review.schoolId === SCHOOL && review.academicYearId === year, 'ANNUAL_SCOPE_CHANGED');
    if (array(review.items).some(i => i.id !== j.data.annualItemId || i.description !== annualDescription)) {
      j.result('annualPlan', { skipped: true, reason: 'Existing current-year plan contains items not owned by this run; no plan item or confirmation was written.' });
    } else {
      await mutation('annual-item', school, 'POST', '/catalog/annual-plan/items?schoolId=1', { id: j.data.annualItemId, category: 'STATIONERY', termName: 'Term 1', description: annualDescription, quantity: '1 synthetic unit', estimatedAmount: 100, status: 'PLANNED' }, r => {
        ok(r); check(r.data?.id === j.data.annualItemId && Number(r.data.schoolId) === SCHOOL, 'ANNUAL_ITEM_NOT_SAVED'); return { id: j.data.annualItemId };
      }, { replaySafe: true });
      review = await school.get('/catalog/annual-plan/review?schoolId=1');
      check(review.items?.length === 1 && review.items[0].id === j.data.annualItemId && review.items[0].description === annualDescription && review.academicYearId === year, 'ANNUAL_REVIEW_NOT_EXCLUSIVELY_OWNED');
      const confirmProof = r => { ok(r); check(r.data?.confirmed === true && r.data.id && r.data.fingerprint === review.fingerprint && r.data.itemCount === 1 && r.data.notificationStatus === 'NOT_SENT', 'ANNUAL_CONFIRMATION_NOT_PERSISTED'); return { id: r.data.id, revision: r.data.revision, fingerprint: r.data.fingerprint, notificationStatus: 'NOT_SENT' }; };
      const confirmed = await mutation('annual-confirm', school, 'POST', '/catalog/annual-plan/confirm?schoolId=1', { fingerprint: review.fingerprint }, confirmProof, { replaySafe: true });
      const confirmedAgain = await mutation('annual-confirm-replay', school, 'POST', '/catalog/annual-plan/confirm?schoolId=1', { fingerprint: review.fingerprint }, confirmProof, { replaySafe: true });
      check(confirmed.id === confirmedAgain.id, 'ANNUAL_CONFIRMATION_REPLAY_CHANGED_ID');
      const persisted = await school.get('/catalog/annual-plan/review?schoolId=1');
      check(persisted.confirmation?.id === confirmed.id && persisted.fingerprint === confirmed.fingerprint, 'ANNUAL_CONFIRMATION_READBACK_MISMATCH');
      j.result('annualPlan', { ...confirmed, itemCount: 1, persisted: true, replaySameId: true });
    }
    j.data.completed = true;
  } catch (error) {
    failed = /^[A-Z0-9_]+$/.test(error.message) ? error.message : 'ACCEPTANCE_STOPPED_WITH_UNCONFIRMED_RESULT';
    j.data.failure = failed;
  } finally {
    j.cleanupMode = true; // Independent reserve/deadline: a failed business phase must still revoke access.
    const cleanup = {};
    try { cleanup.schoolLogoutConfirmed = await school.logout(); } catch { cleanup.schoolLogoutConfirmed = false; }
    // Reconcile an ambiguous account creation before cleanup. Never disable another account.
    if (root.token) {
      try {
        const owner = administratorId ? await root.get(`/users/${administratorId}`) : await findAdmin();
        if (owner) {
          check(userOwned(owner), 'CLEANUP_OWNERSHIP_MISMATCH');
          administratorId = Number(owner.id);
          await mutation(`admin-disable-${j.data.attempts}`, root, 'POST', `/users/${administratorId}/disable`, undefined,
            r => { ok(r); return { disabled: true, userId: administratorId }; }, { replaySafe: true });
          cleanup.administratorDisabled = (await root.get(`/users/${administratorId}`)).active === false;
          cleanup.administratorId = administratorId;
        } else cleanup.administratorNotCreated = !j.data.operations['admin-create'];
      } catch { cleanup.administratorDisabled = false; }
    } else if (j.data.operations['admin-create']) cleanup.administratorDisabled = false;
    try { cleanup.superadminLogoutConfirmed = await root.logout(); } catch { cleanup.superadminLogoutConfirmed = false; }
    j.data.cleanup = cleanup;
    if (cleanup.administratorDisabled === false || cleanup.administratorNotCreated === false || cleanup.schoolLogoutConfirmed === false || cleanup.superadminLogoutConfirmed === false) {
      j.data.completed = false; j.data.cleanupRequired = true;
    }
    j.data.lastFinishedAt = new Date().toISOString(); j.save();
    closeSync(lock); unlinkSync(`${path}.lock`);
  }
  return { project: PROJECT, schoolId: SCHOOL, runId: run, completed: j.data.completed, failure: failed,
    results: j.data.results, cleanup: j.data.cleanup, journal: path, requestCount: j.requestCount };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (!options.apply) console.log(JSON.stringify(plan(options), null, 2));
    else { const result = await execute(options); console.log(JSON.stringify(result, null, 2)); if (!result.completed) process.exitCode = 1; }
  } catch (error) {
    console.error(JSON.stringify({ completed: false, failure: /^[A-Z0-9_]+$/.test(error.message) ? error.message : 'RUNNER_FAILED_NO_RAW_ERROR_REPORTED' }));
    process.exitCode = 1;
  }
}
