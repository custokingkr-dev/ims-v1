import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const fixturesFile = __ENV.K6_FIXTURES_FILE || './fixtures.json';
const fixtures = new SharedArray('school fixtures', () => {
  if (__ENV.K6_ACCESS_TOKEN && __ENV.K6_SCHOOL_ID) {
    return [{
      schoolId: __ENV.K6_SCHOOL_ID,
      accessToken: __ENV.K6_ACCESS_TOKEN,
    }];
  }
  return JSON.parse(open(fixturesFile));
});
const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const attendanceDate = __ENV.ATTENDANCE_DATE || new Date().toISOString().slice(0, 10);

if (!baseUrl) throw new Error('BASE_URL is required');
if (fixtures.length === 0) throw new Error('At least one school fixture is required');

export const options = {
  scenarios: {
    school_day_reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '2m', target: Number(__ENV.PEAK_VUS || 100) },
        { duration: __ENV.HOLD || '10m', target: Number(__ENV.PEAK_VUS || 100) },
        { duration: __ENV.RAMP_DOWN || '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800', 'p(99)<2000'],
    checks: ['rate>0.99'],
  },
};

function request(path, fixture, tags) {
  const response = http.get(`${baseUrl}${path}`, {
    headers: { Authorization: `Bearer ${fixture.accessToken}` },
    tags,
  });
  check(response, {
    'status is successful': (r) => r.status >= 200 && r.status < 400,
    'response is not a server error': (r) => r.status < 500,
  });
}

export default function () {
  const fixture = fixtures[(__VU + __ITER) % fixtures.length];
  const school = encodeURIComponent(fixture.schoolId);

  request(`/api/v1/students?schoolId=${school}&page=0&size=50`, fixture, { flow: 'student-list' });
  sleep(Math.random() * 2);
  request(`/api/v1/dashboard/command-center?schoolId=${school}`, fixture, { flow: 'dashboard' });
  sleep(Math.random() * 2);
  request(`/api/v1/attendance/daily-summary?schoolId=${school}&date=${attendanceDate}`, fixture, { flow: 'attendance-summary' });
  sleep(1 + Math.random() * 4);
}
