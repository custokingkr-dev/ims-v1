# Editable Students Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a school admin edit a student — all demographic/contact fields plus a class/section transfer — via an editable detail modal backed by a new update endpoint.

**Architecture:** New `PUT /api/v1/workspace/students/{id}` mirroring the create path (internal token + `applyResolvedSchool`), delegating to `StudentReadRepository.updateStudent` (tenant re-check, class/section-in-school validation, admission-no uniqueness, version bump). The student detail response gains `classId`/`sectionId` so the modal's dropdowns pre-select. `StudentsPanel`'s View modal gains an Edit mode gated on `student:update`.

**Tech Stack:** Spring Boot 4.0.7 / Java 25, `JdbcClient`; React 18 + Vite + TS.

## Global Constraints

- **No automated tests this pass** (per decision) — verify by compile (backend) + `npm run build` (frontend) + a manual dev check.
- **Editable:** `full_name`, `roll_no`, `admission_no`, `board_reg_no`, `dob`, `gender`, `father_name`, `father_contact`, `mother_name`, `phone`, address components (`house_number/street/locality/city/state/pin_code` → also rebuilds the `address` text), `class_id`, `section_id`. **Not editable:** `school_id`, `academic_year_id`, `photo_url` (existing separate flow).
- **Required (kept):** `full_name`, `class_id`, `section_id`, `phone`.
- **Tenant safety:** the student's `school_id` must equal the resolved school → else 403 (`SecurityException`); a class/section transfer target must belong to the student's own school.
- **Mirror the create path exactly** for token/scope/error mapping (`POST /api/v1/workspace/students` → `createFromWorkspace` → `applyResolvedSchool` → repo; `IllegalArgumentException`→400).
- Build with **JDK 25** (`C:\Program Files\Java\jdk-25.0.3`).

---

### Task 1: Backend — `updateStudent` repo + PUT endpoint + `classId`/`sectionId` in detail

**Files:**
- Modify: `services/school-core-service/.../persistence/StudentReadRepository.java`
- Modify: `services/school-core-service/.../api/compat/StudentWorkspaceCompatibilityController.java`

**Interfaces:**
- Consumes: existing helpers `requireText`, `firstPresent`, `str`, `longValue`, `parseDate`, `joinAddress`, `studentDetail(Long)`, and the create endpoint's `applyResolvedSchool`.
- Produces: `Map<String,Object> updateStudent(Long id, Map request)`; `PUT /api/v1/workspace/students/{id}`; detail response includes `classId`, `sectionId`.

- [ ] **Step 1: Add `classId`/`sectionId` to the student detail**

In the detail method behind `GET /api/v1/students/{id}` (it is `workspaceStudentDetail`, and/or `studentDetail` if `createStudent` returns a different one — add to **both** if they differ; the frontend modal loads `GET /students/{id}`): add `s.class_id`, `s.section_id` to the SELECT and these to the returned map:

```java
                    detail.put("classId", rs.getString("class_id"));
                    detail.put("sectionId", rs.getString("section_id"));
```
(Place alongside the existing `detail.put(...)` lines; add `s.class_id, s.section_id` to the SELECT column list. Keep all existing keys.)

- [ ] **Step 2: Add `updateStudent` to `StudentReadRepository`**

Add after `createStudent`:

```java
    @Transactional
    public Map<String, Object> updateStudent(Long id, Map<String, Object> request) {
        Map<String, Object> current = jdbc.sql("""
                SELECT id, school_id FROM student.students WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, n) -> row("id", rs.getLong("id"), "schoolId", rs.getLong("school_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long studentSchool = longValue(current.get("schoolId"), null);
        Long resolved = longValue(request.get("schoolId"), null);
        if (resolved != null && !resolved.equals(studentSchool)) {
            throw new SecurityException("You do not have access to this student");
        }

        String fullName = requireText(request.get("fullName"), "Full name is mandatory");
        String admissionNo = requireText(firstPresent(request, "admissionNumber", "admissionNo"),
                "Admission Number is mandatory");
        Long dup = jdbc.sql("""
                SELECT COUNT(*) FROM student.students
                WHERE lower(admission_no) = lower(:admissionNo) AND id <> :id AND deleted_at IS NULL
                """)
                .param("admissionNo", admissionNo).param("id", id).query(Long.class).single();
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("Admission Number already exists");
        }

        String classId = requireText(firstPresent(request, "classId", "class_id"), "Class is required");
        String sectionId = requireText(firstPresent(request, "sectionId", "section_id"), "Section is required");
        long sectionOk = jdbc.sql("""
                SELECT COUNT(*) FROM tenant_school.school_sections
                WHERE id = :sectionId AND school_class_id = :classId AND school_id = :schoolId
                """)
                .param("sectionId", sectionId).param("classId", classId).param("schoolId", studentSchool)
                .query(Long.class).single();
        if (sectionOk == 0) {
            throw new IllegalArgumentException("Selected section does not belong to the class and school");
        }

        String phone = requireText(request.get("phone"), "Phone is required");
        String address = joinAddress(
                str(request.get("houseNumber"), ""), str(request.get("street"), ""),
                str(request.get("locality"), ""), str(request.get("city"), ""),
                str(request.get("state"), ""), str(request.get("pinCode"), ""));

        try {
            jdbc.sql("""
                    UPDATE student.students SET
                        full_name = :fullName, roll_no = :rollNo, admission_no = :admissionNo,
                        board_reg_no = :boardRegNo, dob = :dob, gender = :gender,
                        father_name = :fatherName, father_contact = :fatherContact, mother_name = :motherName,
                        phone = :phone, address = :address, house_number = :houseNumber, street = :street,
                        locality = :locality, city = :city, state = :state, pin_code = :pinCode,
                        class_id = :classId, section_id = :sectionId,
                        updated_at = :now, updated_by = :updatedBy, version = version + 1
                    WHERE id = :id AND deleted_at IS NULL
                    """)
                    .param("id", id)
                    .param("fullName", fullName)
                    .param("rollNo", str(request.get("rollNo"), ""))
                    .param("admissionNo", admissionNo)
                    .param("boardRegNo", str(firstPresent(request, "boardRegistrationNumber", "boardRegNo"), ""))
                    .param("dob", parseDate(str(firstPresent(request, "dateOfBirth", "dob"), "")))
                    .param("gender", str(request.get("gender"), "Unspecified"))
                    .param("fatherName", str(request.get("fatherName"), ""))
                    .param("fatherContact", str(firstPresent(request, "fatherContactNumber", "fatherContact"), ""))
                    .param("motherName", str(request.get("motherName"), ""))
                    .param("phone", phone)
                    .param("address", address)
                    .param("houseNumber", str(request.get("houseNumber"), ""))
                    .param("street", str(request.get("street"), ""))
                    .param("locality", str(request.get("locality"), ""))
                    .param("city", str(request.get("city"), ""))
                    .param("state", str(request.get("state"), ""))
                    .param("pinCode", str(request.get("pinCode"), ""))
                    .param("classId", classId)
                    .param("sectionId", sectionId)
                    .param("now", OffsetDateTime.now())
                    .param("updatedBy", str(firstPresent(request, "updatedBy", "actorId"), null))
                    .update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Backstop for the (school_id, admission_no) unique constraint.
            throw new IllegalArgumentException("Admission Number already exists");
        }
        return studentDetail(id);
    }
```

> Use the same detail method `createStudent` returns (`studentDetail(id)`). If `createStudent` returns `studentDetail` and the GET endpoint uses `workspaceStudentDetail`, ensure BOTH include `classId`/`sectionId` (Step 1) so the create-response and the modal agree. If they are the same method, one edit suffices.

- [ ] **Step 3: Add the PUT endpoint (mirror `createFromWorkspace`)**

In `StudentWorkspaceCompatibilityController`, after `createFromWorkspace`:

```java
    @PutMapping("/api/v1/workspace/students/{id}")
    public Map<String, Object> updateFromWorkspace(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:read");
        Map<String, Object> mutableRequest = new HashMap<>(request);
        applyResolvedSchool(mutableRequest);
        try {
            return students.updateStudent(id, mutableRequest);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
```

(`@PutMapping` is already imported. Confirm `applyResolvedSchool` is the same helper `createFromWorkspace` uses.)

- [ ] **Step 4: Compile**

Run:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml -DskipTests compile
```
Expected: BUILD SUCCESS. Also run the student suite to confirm no regression (the detail SELECT change is additive): `.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Student*"` — if any test asserts the exact detail key set, it should still pass (keys added, none removed); note any it touches.

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/StudentWorkspaceCompatibilityController.java
git commit -m "feat(student): PUT /workspace/students/{id} update (incl. class/section transfer) + classId/sectionId in detail"
```

---

### Task 2: Frontend — editable student detail modal

**Files:**
- Modify: `frontend/src/pages/workspace/panels/StudentsPanel.tsx`

**Interfaces:**
- Consumes: `PUT /api/v1/workspace/students/{id}`; detail response now includes `classId`, `sectionId`, `address` (object), `dateOfBirth`, `gender`, `boardRegistrationNumber`, `motherName`, `phone`.

- [ ] **Step 1: Add edit state + class/section option loading**

In `StudentsPanel`, add near the other state:

```tsx
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<Record<string, any>>({});
  const [modalError, setModalError] = useState<string | null>(null);
  const [classOptions, setClassOptions] = useState<Array<{ id: string; name: string }>>([]);
  const [sectionOptions, setSectionOptions] = useState<Array<{ id: string; name: string }>>([]);
```

Load classes when entering edit mode, and sections when the form's class changes:

```tsx
  const startEdit = () => {
    if (!studentDetail) return;
    setModalError(null);
    setForm({
      fullName: studentDetail.fullName ?? '',
      rollNo: studentDetail.rollNo ?? '',
      admissionNumber: studentDetail.admissionNumber ?? '',
      boardRegistrationNumber: studentDetail.boardRegistrationNumber ?? '',
      dateOfBirth: studentDetail.dateOfBirth ?? '',
      gender: studentDetail.gender ?? '',
      fatherName: studentDetail.fatherName ?? '',
      fatherContact: studentDetail.fatherContact ?? '',
      motherName: studentDetail.motherName ?? '',
      phone: studentDetail.phone ?? '',
      houseNumber: studentDetail.address?.houseNumber ?? '',
      street: studentDetail.address?.street ?? '',
      locality: studentDetail.address?.locality ?? '',
      city: studentDetail.address?.city ?? '',
      state: studentDetail.address?.state ?? '',
      pinCode: studentDetail.address?.pinCode ?? '',
      classId: studentDetail.classId ?? '',
      sectionId: studentDetail.sectionId ?? '',
    });
    setEditing(true);
    void api.get('/classes', { params: schoolScopedParams }).then((r) => setClassOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setClassOptions([]));
    if (studentDetail.classId) {
      void api.get(`/classes/${encodeURIComponent(studentDetail.classId)}/sections`, { params: schoolScopedParams })
        .then((r) => setSectionOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setSectionOptions([]));
    }
  };

  const onClassChange = (classId: string) => {
    setForm((f) => ({ ...f, classId, sectionId: '' }));
    void api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams })
      .then((r) => setSectionOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setSectionOptions([]));
  };

  const saveStudent = async () => {
    if (!studentDetail) return;
    setSaving(true);
    setModalError(null);
    try {
      await api.put(`/workspace/students/${studentDetail.id}`, { ...form, ...(schoolScopedParams || {}) });
      const res = await api.get(`/students/${studentDetail.id}`);
      setStudentDetail(res.data);
      setEditing(false);
      await loadStudents(studentFilters, studentsPage);
    } catch (err: unknown) {
      setModalError((err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : 'Could not save changes.'));
    } finally {
      setSaving(false);
    }
  };
```

When the modal closes (`setStudentModalOpen(false)`), also reset `editing`/`modalError` to false/null (update the existing close handlers).

- [ ] **Step 2: Render Edit toggle + edit form in the modal**

In the modal header, add an Edit button (only with the permission, only in view mode):

```tsx
            <div className="ck-modal-title">Student details</div>
            {can('student:update') && !editing && studentDetail && (
              <button className="ck-btn ck-btn-ghost ck-btn-sm" onClick={startEdit}>Edit</button>
            )}
```

In the modal body, when `editing`, render inputs instead of the read-only `Info`/address blocks. Convert every editable field to an input, class/section to `<select>`s:

```tsx
              {editing ? (
                <div className="ck-student-modal-info">
                  {modalError && <div className="ck-alert ck-alert-r" style={{ gridColumn: '1/-1' }}><span>!</span><div>{modalError}</div></div>}
                  <label>Full name<input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></label>
                  <label>Admission No<input value={form.admissionNumber} onChange={(e) => setForm({ ...form, admissionNumber: e.target.value })} /></label>
                  <label>Roll No<input value={form.rollNo} onChange={(e) => setForm({ ...form, rollNo: e.target.value })} /></label>
                  <label>Board Reg No<input value={form.boardRegistrationNumber} onChange={(e) => setForm({ ...form, boardRegistrationNumber: e.target.value })} /></label>
                  <label>Class<select value={form.classId} onChange={(e) => onClassChange(e.target.value)}>
                    <option value="">Select class</option>
                    {classOptions.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select></label>
                  <label>Section<select value={form.sectionId} onChange={(e) => setForm({ ...form, sectionId: e.target.value })} disabled={!form.classId}>
                    <option value="">Select section</option>
                    {sectionOptions.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select></label>
                  <label>Date of birth<input type="date" value={form.dateOfBirth || ''} onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} /></label>
                  <label>Gender<input value={form.gender} onChange={(e) => setForm({ ...form, gender: e.target.value })} /></label>
                  <label>Father name<input value={form.fatherName} onChange={(e) => setForm({ ...form, fatherName: e.target.value })} /></label>
                  <label>Father contact<input value={form.fatherContact} onChange={(e) => setForm({ ...form, fatherContact: e.target.value })} /></label>
                  <label>Mother name<input value={form.motherName} onChange={(e) => setForm({ ...form, motherName: e.target.value })} /></label>
                  <label>Phone<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
                  <label>House / building<input value={form.houseNumber} onChange={(e) => setForm({ ...form, houseNumber: e.target.value })} /></label>
                  <label>Street<input value={form.street} onChange={(e) => setForm({ ...form, street: e.target.value })} /></label>
                  <label>Locality<input value={form.locality} onChange={(e) => setForm({ ...form, locality: e.target.value })} /></label>
                  <label>City<input value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} /></label>
                  <label>State<input value={form.state} onChange={(e) => setForm({ ...form, state: e.target.value })} /></label>
                  <label>PIN code<input value={form.pinCode} onChange={(e) => setForm({ ...form, pinCode: e.target.value })} /></label>
                </div>
              ) : (
                /* existing read-only detail blocks unchanged */
              )}
```

Wrap the existing read-only detail JSX (the hero, `ck-student-modal-info` `Info` grid, address card, attendance/fees card) in the `: (...)` branch of the `editing ?` ternary — keep it exactly as-is for view mode.

- [ ] **Step 3: Edit-mode footer (Save / Cancel)**

Replace the modal footer's single Close button with a mode-aware footer:

```tsx
            <div className="ck-modal-foot">
              {editing ? (
                <>
                  <button className="ck-btn ck-btn-ghost" onClick={() => { setEditing(false); setModalError(null); }} disabled={saving}>Cancel</button>
                  <button className="ck-btn ck-btn-g" onClick={saveStudent} disabled={saving}>{saving ? 'Saving…' : 'Save changes'}</button>
                </>
              ) : (
                <button className="ck-btn ck-btn-ghost" onClick={() => setStudentModalOpen(false)}>Close</button>
              )}
            </div>
```

- [ ] **Step 4: Build**

Run:
```bash
cd frontend
npm run build
```
Expected: build succeeds. (If the existing modal markup differs slightly, adapt the anchors — the intent is: Edit button in header when `can('student:update')`; inputs in edit mode; Save/Cancel footer; on save PUT then reload detail + list.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/StudentsPanel.tsx
git commit -m "feat(student-ui): editable student detail modal (fields + class/section transfer)"
```

---

## Self-Review Notes

- **Spec coverage:** update endpoint + repo with tenant re-check/validation/uniqueness (T1); detail gains classId/sectionId (T1); editable modal gated on `student:update` with class/section dropdowns (T2). All spec sections covered.
- **Tenant safety:** `updateStudent` 403s on a school mismatch and rejects a transfer to a section outside the student's school; the admission-dup check + DIV backstop prevent a 500.
- **Additive detail change:** adding `classId`/`sectionId` keys doesn't remove any existing key, so the read-only modal + other consumers are unaffected.
- **No tests this pass** (per decision) — compile + build + manual dev check (edit a contact, transfer a section, confirm persistence + new-roster membership).
- **Out of scope:** promotion/inter-school transfer, strict optimistic locking, photo editing, bulk transfer.
```