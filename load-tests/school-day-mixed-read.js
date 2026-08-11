import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const accessTokens = (__ENV.K6_ACCESS_TOKENS || __ENV.K6_ACCESS_TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const loginEmail = (__ENV.K6_LOGIN_EMAIL || '').trim();
const loginPassword = __ENV.K6_LOGIN_PASSWORD || '';
const baseSchoolId = Number(__ENV.SCALE_BASE_SCHOOL_ID || 900000000);
const schoolCount = Number(__ENV.SCALE_SCHOOL_COUNT || 100);
const peakVus = Number(__ENV.PEAK_VUS || 300);
const tokenRefreshMillis = Number(__ENV.K6_TOKEN_REFRESH_MILLIS || 12 * 60 * 1000);
const iterationSleepSeconds = Number(__ENV.ITERATION_SLEEP_SECONDS || 1);
const attendanceDate = __ENV.ATTENDANCE_DATE || new Date().toISOString().slice(0, 10);

let vuAccessToken = '';
let vuAccessTokenRefreshAt = 0;

const successfulResponses = new Counter('mixed_http_2xx');
const clientErrors = new Counter('mixed_http_4xx');
const serverErrors = new Counter('mixed_http_5xx');

const flows = [
  {
    name: 'student-list',
    path: (schoolId) => `/api/v1/students?schoolId=${schoolId}&page=0&size=50`,
  },
  {
    name: 'dashboard-command-center',
    path: (schoolId) => `/api/v1/dashboard/command-center?schoolId=${schoolId}`,
  },
  {
    name: 'attendance-daily-summary',
    path: (schoolId) => `/api/v1/attendance/daily-summary?schoolId=${schoolId}`
      + `&date=${encodeURIComponent(attendanceDate)}`,
  },
  {
    name: 'fee-structure',
    path: (schoolId) => `/api/v1/fee-structure?schoolId=${schoolId}`,
  },
  {
    name: 'fee-defaulters',
    path: (schoolId) => `/api/v1/dashboard/finance/fee-defaulters?schoolId=${schoolId}`
      + '&page=0&size=20',
  },
  {
    name: 'attendance-report-summary',
    path: (schoolId) => `/api/v1/attendance/report/summary?schoolId=${schoolId}`
      + `&from=${encodeURIComponent(attendanceDate)}&to=${encodeURIComponent(attendanceDate)}`,
  },
];

if (!baseUrl) throw new Error('BASE_URL is required');
if (accessTokens.length === 0 && (!loginEmail || !loginPassword)) {
  throw new Error('K6_ACCESS_TOKEN(S) or K6_LOGIN_EMAIL/K6_LOGIN_PASSWORD is required');
}
if (!baseUrl.includes('-dev-') && !baseUrl.includes('localhost')) {
  throw new Error('Mixed read load is restricted to dev or localhost');
}
if (baseSchoolId < 900000000 || schoolCount < 1 || schoolCount > 500) {
  throw new Error('Synthetic scale fixture parameters are outside the reserved range');
}
if (peakVus < 1 || peakVus > 300) {
  throw new Error('PEAK_VUS must be between 1 and the certified 300-VU ceiling');
}

const thresholds = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
  'mixed_http_4xx': ['count==0'],
  'mixed_http_5xx': ['count==0'],
};

for (const flow of flows) {
  thresholds[`http_req_duration{flow:${flow.name}}`] = ['p(95)<800', 'p(99)<2000'];
  thresholds[`mixed_http_2xx{flow:${flow.name}}`] = ['count>0'];
  thresholds[`mixed_http_4xx{flow:${flow.name}}`] = ['count==0'];
  thresholds[`mixed_http_5xx{flow:${flow.name}}`] = ['count==0'];
}
if (accessTokens.length === 0) {
  thresholds['mixed_http_2xx{flow:synthetic-login}'] = ['count>0'];
  thresholds['mixed_http_4xx{flow:synthetic-login}'] = ['count==0'];
  thresholds['mixed_http_5xx{flow:synthetic-login}'] = ['count==0'];
}

export const options = {
  scenarios: {
    mixed_school_morning_reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '30s', target: peakVus },
        { duration: __ENV.HOLD || '15m', target: peakVus },
        { duration: __ENV.RAMP_DOWN || '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
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

export default function () {
  // Three VUs cover each of the 100 schools at 300 VUs. Every VU rotates through all flows,
  // which keeps request mix stable without generating six requests at once per iteration.
  const schoolId = baseSchoolId + ((__VU - 1) % schoolCount);
  const flow = flows[(__ITER + __VU - 1) % flows.length];
  const response = http.get(`${baseUrl}${flow.path(schoolId)}`, {
    headers: { Authorization: `Bearer ${tokenForVu()}` },
    tags: { flow: flow.name, name: flow.name },
  });
  recordStatus(flow.name, response.status);
  check(response, {
    [`${flow.name} returns HTTP 200`]: (r) => r.status === 200,
  });
  sleep(iterationSleepSeconds);
}
