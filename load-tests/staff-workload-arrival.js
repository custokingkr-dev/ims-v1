// Staff-workload arrival-rate profile.
//
// WHY THIS EXISTS, AND WHY IT IS NOT ANOTHER VU RAMP
//
// The existing profiles ramp virtual users against the 200,000-student assumption that shaped the
// original scale plan. That assumption does not hold: the system has no student or parent
// authentication, only seven staff roles, so the real population is roughly 20 active staff per
// school — about 3,008 users at the 150-school target rather than 300,000.
//
// Re-deriving load from that population gives a peak of roughly 47 requests/second at 150 schools,
// against a stack already measured at 244 req/s. The open question is therefore not "how many
// concurrent users survive" but "does a smaller database hold the arrival rate real staff produce".
// That is a rate question, so this profile drives a target requests/second with an arrival-rate
// executor instead of a VU count. VU count becomes an output, not an input.
//
// Flow weights come from the persona model: per school per day, teachers generate 14 x 110
// requests, school admin 1 x 400, accountants 2 x 320, operations 2 x 260, viewer 1 x 60.
//
// Read-only by default. Attendance writes are behind ALLOW_WRITES, matching the -AllowScaleWrites
// convention in the certification script.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const accessTokens = (__ENV.K6_ACCESS_TOKENS || __ENV.K6_ACCESS_TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const loginEmail = (__ENV.K6_LOGIN_EMAIL || '').trim();
const loginPassword = __ENV.K6_LOGIN_PASSWORD || '';
const baseSchoolId = Number(__ENV.SCALE_BASE_SCHOOL_ID || 900000000);
const schoolCount = Number(__ENV.SCALE_SCHOOL_COUNT || 150);
const tokenRefreshMillis = Number(__ENV.K6_TOKEN_REFRESH_MILLIS || 12 * 60 * 1000);
const attendanceDate = __ENV.ATTENDANCE_DATE || new Date().toISOString().slice(0, 10);

// Modelled at 150 schools: 16.6 req/s daily average, 46.6 req/s in the morning attendance burst.
// PEAK_RPS is deliberately a little above the model so a pass carries headroom.
const baselineRps = Number(__ENV.BASELINE_RPS || 17);
const peakRps = Number(__ENV.PEAK_RPS || 50);
// Arrival-rate executors need a VU pool large enough to sustain the rate. At a 500 ms response the
// pool must exceed rate/2; this default carries roughly 10x that headroom so the rate, not the
// pool, is the binding constraint. If k6 warns about insufficient VUs, the run is invalid.
const maxVus = Number(__ENV.MAX_VUS || 300);

let vuAccessToken = '';
let vuAccessTokenRefreshAt = 0;

const successfulResponses = new Counter('staff_http_2xx');
const clientErrors = new Counter('staff_http_4xx');
const serverErrors = new Counter('staff_http_5xx');
const observedRps = new Trend('staff_observed_rps');

// weight = share of all staff requests, from the persona model. Normalised at load.
const flows = [
  // Teachers are 14 of 20 staff and generate 48.7% of school traffic.
  { name: 'teacher-attendance-summary', persona: 'teacher', weight: 16.2,
    path: (s) => `/api/v1/attendance/daily-summary?schoolId=${s}&date=${encodeURIComponent(attendanceDate)}` },
  { name: 'teacher-student-list', persona: 'teacher', weight: 16.2,
    path: (s) => `/api/v1/students?schoolId=${s}&page=0&size=50` },
  { name: 'teacher-attendance-report', persona: 'teacher', weight: 16.3,
    path: (s) => `/api/v1/attendance/report/summary?schoolId=${s}`
      + `&from=${encodeURIComponent(attendanceDate)}&to=${encodeURIComponent(attendanceDate)}` },

  // Accountants: 20.3%.
  { name: 'accountant-fee-structure', persona: 'accountant', weight: 10.1,
    path: (s) => `/api/v1/fee-structure?schoolId=${s}` },
  { name: 'accountant-fee-defaulters', persona: 'accountant', weight: 10.2,
    path: (s) => `/api/v1/dashboard/finance/fee-defaulters?schoolId=${s}&page=0&size=20` },

  // Operations: 16.5%.
  { name: 'operations-command-center', persona: 'operations', weight: 8.2,
    path: (s) => `/api/v1/dashboard/command-center?schoolId=${s}` },
  { name: 'operations-student-list', persona: 'operations', weight: 8.3,
    path: (s) => `/api/v1/students?schoolId=${s}&page=0&size=20` },

  // School admin: 12.7%.
  { name: 'admin-command-center', persona: 'admin', weight: 4.2,
    path: (s) => `/api/v1/dashboard/command-center?schoolId=${s}` },
  { name: 'admin-student-list', persona: 'admin', weight: 4.2,
    path: (s) => `/api/v1/students?schoolId=${s}&page=0&size=100` },
  { name: 'admin-attendance-report', persona: 'admin', weight: 4.3,
    path: (s) => `/api/v1/attendance/report/summary?schoolId=${s}`
      + `&from=${encodeURIComponent(attendanceDate)}&to=${encodeURIComponent(attendanceDate)}` },

  // Viewer / principal: 1.9%.
  { name: 'viewer-command-center', persona: 'viewer', weight: 1.9,
    path: (s) => `/api/v1/dashboard/command-center?schoolId=${s}` },
];

if (!baseUrl) throw new Error('BASE_URL is required');
if (accessTokens.length === 0 && (!loginEmail || !loginPassword)) {
  throw new Error('K6_ACCESS_TOKEN(S) or K6_LOGIN_EMAIL/K6_LOGIN_PASSWORD is required');
}
if (!baseUrl.includes('-dev-') && !baseUrl.includes('localhost')) {
  throw new Error('Staff workload load is restricted to dev or localhost');
}
if (baseSchoolId < 900000000 || schoolCount < 1 || schoolCount > 500) {
  throw new Error('Synthetic scale fixture parameters are outside the reserved range');
}
if (peakRps < 1 || peakRps > 300) {
  throw new Error('PEAK_RPS must be between 1 and 300; higher rates need a separate sizing experiment');
}
if (baselineRps < 1 || baselineRps > peakRps) {
  throw new Error('BASELINE_RPS must be at least 1 and no greater than PEAK_RPS');
}

const totalWeight = flows.reduce((sum, flow) => sum + flow.weight, 0);
let cumulative = 0;
for (const flow of flows) {
  cumulative += flow.weight / totalWeight;
  flow.threshold = cumulative;
}

// Thresholds encode the hypothesis: at staff arrival rates the smaller database should hold
// comfortably, so these are tighter than the 800 ms used for the 300-VU student-scale profiles.
// A failure here is the signal that the smaller shape does not work.
const thresholds = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
  http_req_duration: ['p(95)<500', 'p(99)<1500'],
  staff_http_4xx: ['count==0'],
  staff_http_5xx: ['count==0'],
  // Confirms the executor actually delivered the requested rate. If k6 could not keep up, the
  // run proves nothing about the database.
  dropped_iterations: ['count==0'],
};
for (const flow of flows) {
  thresholds[`http_req_duration{flow:${flow.name}}`] = ['p(95)<500', 'p(99)<1500'];
  thresholds[`staff_http_5xx{flow:${flow.name}}`] = ['count==0'];
}
if (accessTokens.length === 0) {
  thresholds['staff_http_4xx{flow:synthetic-login}'] = ['count==0'];
  thresholds['staff_http_5xx{flow:synthetic-login}'] = ['count==0'];
}

export const options = {
  scenarios: {
    // Shape of a school day: a quiet baseline, the morning attendance burst, then back to baseline.
    // Total default duration 70 minutes, long enough to expose connection-pool and cache effects
    // that a short burst hides.
    staff_school_day: {
      executor: 'ramping-arrival-rate',
      startRate: baselineRps,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(maxVus, Math.max(20, Math.ceil(peakRps / 2))),
      maxVUs: maxVus,
      stages: [
        { duration: __ENV.WARMUP || '5m', target: baselineRps },   // pre-school baseline
        { duration: __ENV.RAMP_UP || '3m', target: peakRps },      // staff arrive, attendance opens
        { duration: __ENV.BURST || '20m', target: peakRps },       // morning attendance burst
        { duration: __ENV.RAMP_DOWN || '3m', target: baselineRps },// burst subsides
        { duration: __ENV.HOLD || '35m', target: baselineRps },    // rest of the school day
        { duration: '2m', target: 0 },
      ],
      gracefulStop: '30s',
    },
  },
  thresholds,
};

function recordStatus(flow, status) {
  const tags = { flow };
  if (status >= 200 && status < 300) successfulResponses.add(1, tags);
  else if (status >= 400 && status < 500) clientErrors.add(1, tags);
  else if (status >= 500) serverErrors.add(1, tags);
}

function tokenForVu() {
  if (accessTokens.length > 0) {
    return accessTokens[(__VU - 1) % accessTokens.length];
  }
  if (!vuAccessToken || Date.now() >= vuAccessTokenRefreshAt) {
    const response = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
      email: loginEmail,
      password: loginPassword,
    }), {
      headers: { 'Content-Type': 'application/json' },
      tags: { flow: 'synthetic-login', name: 'synthetic-login' },
    });
    recordStatus('synthetic-login', response.status);
    if (!check(response, { 'synthetic login succeeds': (r) => r.status === 200 })) {
      fail(`Synthetic login failed with HTTP ${response.status}`);
    }
    const payload = response.json();
    if (!payload.accessToken) fail('Synthetic login response did not contain accessToken');
    vuAccessToken = payload.accessToken;
    vuAccessTokenRefreshAt = Date.now() + tokenRefreshMillis;
  }
  return vuAccessToken;
}

function pickFlow() {
  // Weighted pick, so the request mix matches the persona model rather than rotating uniformly.
  // Uniform rotation would over-represent the rare admin and viewer flows and under-represent
  // teacher traffic, which is half of all real requests.
  const roll = Math.random();
  for (const flow of flows) {
    if (roll <= flow.threshold) return flow;
  }
  return flows[flows.length - 1];
}

export default function () {
  // Each iteration is one request, so the arrival rate is the request rate. Schools are spread
  // across the synthetic fixture range so the database sees tenant-diverse access, which is what
  // exercises row-level security and the tenant-leading indexes.
  const schoolId = baseSchoolId + Math.floor(Math.random() * schoolCount);
  const flow = pickFlow();
  const response = http.get(`${baseUrl}${flow.path(schoolId)}`, {
    headers: { Authorization: `Bearer ${tokenForVu()}` },
    tags: { flow: flow.name, persona: flow.persona, name: flow.name },
  });
  recordStatus(flow.name, response.status);
  check(response, { [`${flow.name} responds 2xx`]: (r) => r.status >= 200 && r.status < 300 });
}

export function handleSummary(data) {
  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values : {};
  const dur = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
  const dropped = data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0;
  const lines = [
    '',
    'STAFF WORKLOAD ARRIVAL PROFILE',
    `  schools modelled     ${schoolCount}`,
    `  baseline / peak rps  ${baselineRps} / ${peakRps}`,
    `  achieved rps         ${(reqs.rate || 0).toFixed(1)}`,
    `  requests             ${reqs.count || 0}`,
    `  p95 / p99 ms         ${(dur['p(95)'] || 0).toFixed(0)} / ${(dur['p(99)'] || 0).toFixed(0)}`,
    `  dropped iterations   ${dropped}${dropped > 0 ? '   <-- rate not delivered; run is INVALID' : ''}`,
    '',
  ];
  return {
    stdout: lines.join('\n'),
    'staff-workload-summary.json': JSON.stringify(data, null, 2),
  };
}
