# Absentee Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a school admin review a day's absentees and **queue** a WhatsApp notification to each parent (queue-only; live MSG91 delivery deferred).

**Architecture:** A school-core `attendance.absentee_notifications` table (V8) is the outbox of notification intents. `GET /attendance/absentees` lists a day's ABSENT students with parent contact + already-queued flag; `POST /attendance/absentees/notify` inserts `QUEUED` WhatsApp rows (idempotent via `UNIQUE(student_id, attendance_date)`). A third `Absentees` tab in the attendance module hosts the review + Notify UI.

**Tech Stack:** Spring Boot 4.0.7 / Java 25, `JdbcClient`, Flyway (`attendance` history); React 18 + Vite + TS.

## Global Constraints

- **Manual, queue-only, WhatsApp.** Notify writes `QUEUED` rows; it does NOT call MSG91. The delivery worker is a deferred follow-up.
- **Triggering status: `ABSENT` only.** Recipient = `father_contact` → fallback `phone`; no contact → skipped and surfaced (never queued blank).
- **Idempotency:** one queued notification per student per absence date (`UNIQUE(student_id, attendance_date)` + `ON CONFLICT DO NOTHING`).
- **RLS** on `absentee_notifications` uses the `tenant_isolation` policy shape from `attendance/V6__enable_rls.sql` (GUC `app.current_school_id`, bypass `app.bypass_rls='on'`). Runtime role `app_rt`; migrations run as owner.
- **Scoping:** every endpoint `requireToken` + `TenantScope.resolveSchoolId`. The inserted `school_id` = the absentees' school (must equal the tenant GUC for a school admin — it does).
- **No automated tests this pass** (per decision) — verify by compile (backend) + `npm run build` (frontend) + a manual dev check.
- Build with **JDK 25**. Next `attendance` migration is **V8** (V1–V7 exist).

---

### Task 1: V8 migration + absentee endpoints (backend)

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/attendance/V8__absentee_notifications.sql`
- Modify: `services/school-core-service/.../persistence/AttendanceReadRepository.java`
- Modify: `services/school-core-service/.../api/AttendanceReadController.java`
- Create: `services/school-core-service/.../api/dto/NotifyAbsenteesRequest.java`

**Interfaces:**
- Consumes: existing repo helpers `currentAcademicYearId()`, `row`, `str`, `longNum`, `parseDate`, `round`, field `recordsTable`, and the constructor's `schema` / `qualifiedTable`.
- Produces: `Map<String,Object> absentees(LocalDate, String sectionId, Long schoolId)`, `Map<String,Object> notifyAbsentees(LocalDate, String sectionId, Long schoolId, Long actorId)`; `GET /attendance/absentees`, `POST /attendance/absentees/notify`.

- [ ] **Step 1: Migration**

`V8__absentee_notifications.sql`:

```sql
CREATE TABLE attendance.absentee_notifications (
    id                VARCHAR(255) PRIMARY KEY,
    school_id         BIGINT       NOT NULL,
    student_id        BIGINT       NOT NULL,
    class_id          VARCHAR(255) NOT NULL,
    section_id        VARCHAR(255) NOT NULL,
    academic_year_id  VARCHAR(255) NOT NULL,
    attendance_date   DATE         NOT NULL,
    parent_contact    VARCHAR(255) NOT NULL,
    channel           VARCHAR(20)  NOT NULL DEFAULT 'WHATSAPP',
    message           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    queued_by         BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_absentee_notification UNIQUE (student_id, attendance_date)
);
CREATE INDEX idx_absentee_notif_school_date ON attendance.absentee_notifications (school_id, attendance_date);

ALTER TABLE attendance.absentee_notifications ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON attendance.absentee_notifications;
CREATE POLICY tenant_isolation ON attendance.absentee_notifications
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
```

- [ ] **Step 2: Add the `absenteeTable` field**

In `AttendanceReadRepository`, add a field + initialize it in the constructor alongside `dailyTable`/`recordsTable`:

```java
    private final String absenteeTable;
```
In the constructor (where `dailyTable`/`recordsTable` are set):
```java
        this.absenteeTable = qualifiedTable(schema, "absentee_notifications");
```

- [ ] **Step 3: Add `absentees` and `notifyAbsentees`**

Add these methods (a shared private query builds the absentee rows so list + notify agree):

```java
    public Map<String, Object> absentees(LocalDate date, String sectionId, Long schoolId) {
        List<Map<String, Object>> students = absenteeRows(date, sectionId, schoolId);
        long queued = students.stream().filter(s -> Boolean.TRUE.equals(s.get("alreadyQueued"))).count();
        return row("date", date.toString(),
                "sectionId", sectionId,
                "students", students,
                "totalAbsent", students.size(),
                "queuedCount", queued);
    }

    private List<Map<String, Object>> absenteeRows(LocalDate date, String sectionId, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS student_id, s.full_name, s.admission_no, s.roll_no,
                       s.school_id, ar.class_id, ar.section_id,
                       sc.name AS class_name, ss.name AS section_name,
                       COALESCE(NULLIF(s.father_contact, ''), NULLIF(s.phone, ''), '') AS parent_contact,
                       EXISTS (SELECT 1 FROM %s an
                                WHERE an.student_id = s.id AND an.attendance_date = :date) AS already_queued
                FROM %s ar
                JOIN student.students s ON s.id = ar.student_id AND s.deleted_at IS NULL
                JOIN tenant_school.school_sections ss ON ss.id = ar.section_id
                JOIN tenant_school.school_classes sc ON sc.id = ar.class_id
                WHERE ar.attendance_date = :date AND ar.academic_year_id = :academicYearId
                  AND ar.status = 'ABSENT'
                """.formatted(absenteeTable, recordsTable));
        if (schoolId != null) sql.append(" AND ar.school_id = :schoolId");
        if (sectionId != null && !sectionId.isBlank()) sql.append(" AND ar.section_id = :sectionId");
        sql.append("""
                 ORDER BY NULLIF(regexp_replace(COALESCE(s.roll_no, ''), '[^0-9]', '', 'g'), '')::int NULLS LAST,
                          s.roll_no NULLS LAST, s.full_name
                """);
        var spec = jdbc.sql(sql.toString())
                .param("date", date)
                .param("academicYearId", academicYearId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        return spec.query((rs, n) -> {
            String parent = rs.getString("parent_contact");
            return row("studentId", rs.getLong("student_id"),
                    "fullName", rs.getString("full_name"),
                    "admissionNo", rs.getString("admission_no"),
                    "rollNo", rs.getString("roll_no"),
                    "classSection", rs.getString("class_name") + "-" + rs.getString("section_name"),
                    "schoolId", rs.getLong("school_id"),
                    "classId", rs.getString("class_id"),
                    "sectionId", rs.getString("section_id"),
                    "parentContact", parent,
                    "hasContact", parent != null && !parent.isBlank(),
                    "alreadyQueued", rs.getBoolean("already_queued"));
        }).list();
    }

    @Transactional
    public Map<String, Object> notifyAbsentees(LocalDate date, String sectionId, Long schoolId, Long actorId) {
        String academicYearId = currentAcademicYearId();
        List<Map<String, Object>> students = absenteeRows(date, sectionId, schoolId);
        String schoolName = schoolId == null ? "your school" : jdbc.sql(
                "SELECT name FROM tenant_school.schools WHERE id = :id")
                .param("id", schoolId).query(String.class).optional().orElse("your school");
        String dateLabel = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        int queued = 0, skippedNoContact = 0, skippedAlreadyQueued = 0;
        for (Map<String, Object> s : students) {
            if (Boolean.TRUE.equals(s.get("alreadyQueued"))) { skippedAlreadyQueued++; continue; }
            if (!Boolean.TRUE.equals(s.get("hasContact"))) { skippedNoContact++; continue; }
            String message = "Dear Parent, " + s.get("fullName") + " (" + s.get("classSection")
                    + ") was marked absent on " + dateLabel + " at " + schoolName
                    + ". Please contact the school if this is unexpected.";
            int inserted = jdbc.sql("""
                    INSERT INTO %s(id, school_id, student_id, class_id, section_id, academic_year_id,
                                   attendance_date, parent_contact, channel, message, status, queued_by)
                    VALUES (:id, :schoolId, :studentId, :classId, :sectionId, :academicYearId,
                            :date, :parentContact, 'WHATSAPP', :message, 'QUEUED', :actorId)
                    ON CONFLICT (student_id, attendance_date) DO NOTHING
                    """.formatted(absenteeTable))
                    .param("id", UUID.randomUUID().toString())
                    .param("schoolId", longNum(s.get("schoolId"), 0))
                    .param("studentId", longNum(s.get("studentId"), 0))
                    .param("classId", s.get("classId"))
                    .param("sectionId", s.get("sectionId"))
                    .param("academicYearId", academicYearId)
                    .param("date", date)
                    .param("parentContact", s.get("parentContact"))
                    .param("message", message)
                    .param("actorId", actorId)
                    .update();
            if (inserted > 0) queued++; else skippedAlreadyQueued++;
        }
        return row("date", date.toString(), "queued", queued,
                "skippedNoContact", skippedNoContact, "skippedAlreadyQueued", skippedAlreadyQueued);
    }
```

Ensure `java.time.format.DateTimeFormatter` and `java.util.UUID` are imported (both are already used in this file).

- [ ] **Step 4: DTO + controller endpoints**

Create `NotifyAbsenteesRequest.java`:

```java
package com.custoking.ims.schoolcoreservice.api.dto;

/** Body for POST /attendance/absentees/notify. All optional except date. */
public record NotifyAbsenteesRequest(String date, String sectionId, Long schoolId, Long actorId) {}
```

In `AttendanceReadController`, after `sectionRegister` (GET), add:

```java
    @GetMapping("/absentees")
    public Map<String, Object> absentees(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.absentees(date, sectionId, scope));
    }

    @PostMapping("/absentees/notify")
    public Map<String, Object> notifyAbsentees(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody NotifyAbsenteesRequest body) {
        requireToken(token, "attendance:write");
        Long scope = TenantScope.resolveSchoolId(body.schoolId());
        LocalDate date = body.date() == null || body.date().isBlank() ? LocalDate.now() : LocalDate.parse(body.date());
        return execute(() -> attendance.notifyAbsentees(date, body.sectionId(), scope, body.actorId()));
    }
```

Add the import for the DTO: `import com.custoking.ims.schoolcoreservice.api.dto.NotifyAbsenteesRequest;`.

- [ ] **Step 5: Compile**

Run:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml -DskipTests compile
```
Expected: BUILD SUCCESS. Then `.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Attendance*"` to confirm the new `absenteeTable` field + additive changes didn't break the existing attendance suite (the constructor change is additive; existing tests construct the repo with the same args). Note any test touched.

- [ ] **Step 6: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/attendance/V8__absentee_notifications.sql \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/dto/NotifyAbsenteesRequest.java
git commit -m "feat(attendance): absentee notifications — V8 queue table + list/notify endpoints"
```

---

### Task 2: Frontend — Absentees tab + panel

**Files:**
- Modify: `frontend/src/types/attendance.ts`
- Create: `frontend/src/pages/workspace/panels/AttendanceAbsenteePanel.tsx`
- Modify: `frontend/src/pages/workspace/panels/AttendanceModulePanel.tsx`

**Interfaces:**
- Consumes: `GET /attendance/absentees`, `POST /attendance/absentees/notify`; `ModuleShell`/`Field` from `../ui`; `todayIso` from `../utils`; `api`.
- Produces: `AttendanceAbsenteePanel`; a third `Absentees` tab.

- [ ] **Step 1: Types**

Append to `types/attendance.ts`:

```ts
export interface AbsenteeStudent {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  classSection: string;
  parentContact: string;
  hasContact: boolean;
  alreadyQueued: boolean;
}
export interface AbsenteeListResponse {
  date: string;
  sectionId: string | null;
  students: AbsenteeStudent[];
  totalAbsent: number;
  queuedCount: number;
}
export interface NotifyAbsenteesResponse {
  date: string;
  queued: number;
  skippedNoContact: number;
  skippedAlreadyQueued: number;
}
```

- [ ] **Step 2: `AttendanceAbsenteePanel.tsx`**

```tsx
import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import type { AbsenteeListResponse, NotifyAbsenteesResponse } from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceAbsenteePanel({ schoolScopedParams }: Props) {
  const scoped = schoolScopedParams || {};
  const [date, setDate] = useState(todayIso());
  const [data, setData] = useState<AbsenteeListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [notifying, setNotifying] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

  const load = async (d: string) => {
    setLoading(true);
    setError('');
    try {
      const res = await api.get<AbsenteeListResponse>('/attendance/absentees', { params: { date: d, ...scoped } });
      setData(res.data);
    } catch (err) {
      setError(errMessage(err, 'Failed to load absentees'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(date); }, [date]);

  const notifiable = (data?.students || []).filter((s) => s.hasContact && !s.alreadyQueued).length;

  const notify = async () => {
    setNotifying(true);
    setError('');
    setToast('');
    try {
      const res = await api.post<NotifyAbsenteesResponse>('/attendance/absentees/notify', { date, ...scoped });
      const r = res.data;
      setToast(`Queued ${r.queued} · skipped ${r.skippedNoContact + r.skippedAlreadyQueued} (no contact ${r.skippedNoContact}, already queued ${r.skippedAlreadyQueued})`);
      await load(date);
    } catch (err) {
      setError(errMessage(err, 'Could not queue notifications'));
    } finally {
      setNotifying(false);
    }
  };

  return (
    <ModuleShell
      title="Absentee notifications"
      subtitle={`${data?.totalAbsent ?? 0} absent · ${data?.queuedCount ?? 0} queued`}
      actions={
        <button className="ck-btn ck-btn-g" disabled={notifiable === 0 || notifying} onClick={notify}>
          {notifying ? 'Queuing…' : `Notify absent parents (WhatsApp)`}
        </button>
      }
    >
      {toast && <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{toast}</div></div>}
      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{error}</div></div>}

      <div style={{ marginBottom: 16 }}>
        <Field label="Date"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
      </div>

      {loading ? (
        <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading absentees…</div>
      ) : !data || data.students.length === 0 ? (
        <div className="ck-alert ck-alert-am"><span>i</span><div>No absentees for this date.</div></div>
      ) : (
        <div className="ck-att-report-scroll">
          <table className="ck-att-table">
            <thead><tr><th>Student</th><th>Class-Section</th><th>Parent contact</th><th>Status</th></tr></thead>
            <tbody>
              {data.students.map((s) => (
                <tr key={s.studentId}>
                  <td>{s.rollNo ? `${s.rollNo}. ` : ''}{s.fullName} <span style={{ color: 'var(--ink3)' }}>({s.admissionNo})</span></td>
                  <td>{s.classSection}</td>
                  <td>{s.hasContact ? s.parentContact : <span style={{ color: 'var(--ink3)' }}>No contact</span>}</td>
                  <td>{s.alreadyQueued ? <span className="ck-status sapproved">Queued</span> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ModuleShell>
  );
}
```

- [ ] **Step 3: Add the `Absentees` tab to `AttendanceModulePanel`**

Rewrite `AttendanceModulePanel.tsx` to a 3-tab host:

```tsx
import { useState } from 'react';
import { AttendancePanel } from './AttendancePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

type Tab = 'mark' | 'reports' | 'absentees';

export function AttendanceModulePanel({ onRefresh, schoolScopedParams }: Props) {
  const [tab, setTab] = useState<Tab>('mark');
  const label: Record<Tab, string> = { mark: 'Mark', reports: 'Reports', absentees: 'Absentees' };
  return (
    <div>
      <div className="ck-att-tabs">
        {(['mark', 'reports', 'absentees'] as Tab[]).map((t) => (
          <button key={t} type="button" className={`ck-att-tab${tab === t ? ' ck-att-tab--active' : ''}`} onClick={() => setTab(t)}>
            {label[t]}
          </button>
        ))}
      </div>
      {tab === 'mark' && <AttendancePanel onRefresh={onRefresh} schoolScopedParams={schoolScopedParams} />}
      {tab === 'reports' && <AttendanceReportsPanel schoolScopedParams={schoolScopedParams} />}
      {tab === 'absentees' && <AttendanceAbsenteePanel schoolScopedParams={schoolScopedParams} />}
    </div>
  );
}
```

- [ ] **Step 4: Build**

Run:
```bash
cd frontend
npm run build
```
Expected: build succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/attendance.ts \
        frontend/src/pages/workspace/panels/AttendanceAbsenteePanel.tsx \
        frontend/src/pages/workspace/panels/AttendanceModulePanel.tsx
git commit -m "feat(attendance-ui): Absentees tab — review + queue WhatsApp parent notifications"
```

---

## Self-Review Notes

- **Spec coverage:** V8 queue table + RLS (T1); `GET /absentees` (contact + alreadyQueued) + `POST /absentees/notify` (recomputes, queues non-contacted/not-queued, returns summary) (T1); idempotency via unique + `ON CONFLICT DO NOTHING` (T1); Absentees tab + panel (T2). Delivery worker explicitly out of scope.
- **Scoping/RLS:** endpoints resolve the school via `TenantScope`; the queue table has the standard `tenant_isolation` policy; the inserted `school_id` comes from the absentee row's own school (== the tenant GUC for a school admin).
- **Recipient safety:** `parentContact = COALESCE(father_contact, phone, '')`; no-contact students are counted (`skippedNoContact`), never queued with a blank recipient.
- **Notify is server-recomputed** (does not trust a client list); `ON CONFLICT DO NOTHING` makes re-clicks idempotent even under a race.
- **No tests this pass** (per decision) — compile + build + manual dev check (mark a student ABSENT, open Absentees, Notify → `Queued 1`, re-open shows `Queued` + a second Notify reports skippedAlreadyQueued).
- **Out of scope:** the MSG91 WhatsApp delivery worker + template/config, auto/scheduled triggers, per-school opt-in, LATE/SMS/email, delivery receipts.
```