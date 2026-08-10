import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const accessTokens = (__ENV.K6_ACCESS_TOKENS || __ENV.K6_ACCESS_TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const baseSchoolId = Number(__ENV.SCALE_BASE_SCHOOL_ID || 900000000);
const schoolCount = Number(__ENV.SCALE_SCHOOL_COUNT || 100);
const totalStudents = Number(__ENV.SCALE_TOTAL_STUDENTS || 300000);
const largeSchoolStudents = Number(__ENV.SCALE_LARGE_SCHOOL_STUDENTS || 10000);
const attendanceDate = __ENV.ATTENDANCE_DATE || new Date().toISOString().slice(0, 10);
const iterationSleepSeconds = Number(__ENV.ITERATION_SLEEP_SECONDS || 2);
const peakVus = Number(__ENV.PEAK_VUS || 100);

const responsesByClass = {
  success: new Counter('attendance_http_2xx'),
  unauthorized: new Counter('attendance_http_401'),
  forbidden: new Counter('attendance_http_403'),
  notFound: new Counter('attendance_http_404'),
  conflict: new Counter('attendance_http_409'),
  throttled: new Counter('attendance_http_429'),
  other4xx: new Counter('attendance_http_other_4xx'),
  serverError: new Counter('attendance_http_5xx'),
};

if (!baseUrl) throw new Error('BASE_URL is required');
if (accessTokens.length === 0) throw new Error('K6_ACCESS_TOKEN or K6_ACCESS_TOKENS is required');
if (__ENV.ALLOW_SCALE_WRITES !== '1') throw new Error('ALLOW_SCALE_WRITES=1 is required');
if (!baseUrl.includes('-dev-') && !baseUrl.includes('localhost')) {
  throw new Error('Attendance write load is restricted to dev or localhost');
}
if (baseSchoolId < 900000000 || schoolCount < 1 || schoolCount > 500) {
  throw new Error('Synthetic scale fixture parameters are outside the reserved range');
}

function studentsForSchool(index) {
  if (schoolCount === 1) return totalStudents;
  if (index === 0) return largeSchoolStudents;
  const remaining = totalStudents - largeSchoolStudents;
  const base = Math.floor(remaining / (schoolCount - 1));
  const remainder = remaining % (schoolCount - 1);
  return base + (index <= remainder ? 1 : 0);
}

const sections = new SharedArray('synthetic attendance sections', () => {
  const rows = [];
  for (let schoolIndex = 0; schoolIndex < schoolCount; schoolIndex += 1) {
    const schoolId = baseSchoolId + schoolIndex;
    const sectionCount = Math.ceil(studentsForSchool(schoolIndex) / 40);
    for (let sectionNo = 1; sectionNo <= sectionCount; sectionNo += 1) {
      const classNo = ((sectionNo - 1) % 12) + 1;
      rows.push({
        schoolId,
        classId: `scale-c-${String(classNo).padStart(2, '0')}`,
        sectionId: `scale-${schoolId}-s-${String(sectionNo).padStart(4, '0')}`,
      });
    }
  }
  return rows;
});

if (peakVus < 1 || peakVus > sections.length) {
  throw new Error(`PEAK_VUS must be between 1 and the ${sections.length} synthetic sections`);
}

export const options = {
  scenarios: {
    attendance_writes: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '2m', target: Number(__ENV.PEAK_VUS || 100) },
        { duration: __ENV.HOLD || '10m', target: peakVus },
        { duration: __ENV.RAMP_DOWN || '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{flow:attendance-write}': ['p(95)<1500', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};

function headersForVu() {
  const token = accessTokens[(__VU - 1) % accessTokens.length];
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

function recordStatus(operation, status) {
  const tags = { operation };
  if (status >= 200 && status < 300) responsesByClass.success.add(1, tags);
  else if (status === 401) responsesByClass.unauthorized.add(1, tags);
  else if (status === 403) responsesByClass.forbidden.add(1, tags);
  else if (status === 404) responsesByClass.notFound.add(1, tags);
  else if (status === 409) responsesByClass.conflict.add(1, tags);
  else if (status === 429) responsesByClass.throttled.add(1, tags);
  else if (status >= 400 && status < 500) responsesByClass.other4xx.add(1, tags);
  else if (status >= 500) responsesByClass.serverError.add(1, tags);
}

export default function () {
  const headers = headersForVu();
  // Each VU owns a disjoint modulo partition of the fixture. This keeps broad table/index
  // coverage without making multiple synthetic teachers update the same section/date row.
  const ownedSlot = __VU - 1;
  const ownedSectionCount = Math.floor((sections.length - 1 - ownedSlot) / peakVus) + 1;
  const fixture = sections[ownedSlot + ((__ITER % ownedSectionCount) * peakVus)];
  if (!fixture || !String(fixture.sectionId).startsWith('scale-')) {
    fail('Refusing to write outside a synthetic scale section');
  }

  const query = `schoolId=${fixture.schoolId}`
    + `&date=${encodeURIComponent(attendanceDate)}`
    + `&classId=${encodeURIComponent(fixture.classId)}`
    + `&sectionId=${encodeURIComponent(fixture.sectionId)}`;
  const register = http.get(`${baseUrl}/api/v1/attendance/section-register?${query}`, {
    headers,
    tags: { flow: 'attendance-register-read', name: 'attendance-register-read' },
  });
  recordStatus('read', register.status);
  const readOk = check(register, {
    'register read succeeds': (r) => r.status === 200,
  });
  if (!readOk) {
    sleep(iterationSleepSeconds);
    return;
  }

  let payload;
  try {
    payload = register.json();
  } catch (error) {
    fail(`Register response was not JSON: ${error}`);
  }
  if (!payload.students || payload.students.length === 0 || payload.students.length > 40) {
    fail(`Unexpected synthetic section size: ${payload.students ? payload.students.length : 0}`);
  }

  const statuses = ['PRESENT', 'PRESENT', 'PRESENT', 'LATE', 'LEAVE', 'ABSENT'];
  const records = payload.students.map((student, index) => ({
    studentId: student.studentId,
    status: statuses[(index + __ITER) % statuses.length],
    remarks: 'synthetic scale load',
  }));
  const response = http.put(`${baseUrl}/api/v1/attendance/section-register`, JSON.stringify({
    schoolId: fixture.schoolId,
    classId: fixture.classId,
    sectionId: fixture.sectionId,
    date: attendanceDate,
    records,
  }), {
    headers,
    tags: { flow: 'attendance-write', name: 'attendance-write' },
  });
  recordStatus('write', response.status);

  check(response, {
    'attendance write succeeds': (r) => r.status === 200,
    'attendance write is not a server error': (r) => r.status < 500,
  });
  sleep(iterationSleepSeconds);
}
