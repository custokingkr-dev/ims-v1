# Bulk Student Photo Import — Design

**Date:** 2026-07-05
**Status:** Approved (design)
**Service:** `school-core-service` (owns `student` schema + `StudentPhotoStorage`) and `frontend`

---

## Problem

Bulk student import (spreadsheet → students) currently carries **no photos**. Schools
onboarding ~500–600 students want to supply photos in bulk. Photos may be **embedded in
the spreadsheet** or provided as a **link**. Raw external links must never be persisted
(private/expiring links, hotlink/CORS blocking, minors-PII compliance, reliability), so
any photo — embedded or linked — must end up in **our private GCS bucket** via the
existing `StudentPhotoStorage` (resize to 512px, content-addressed, signed-URL display).

Data import must also accept **`.xlsx`, `.xls`, `.ods`, `.csv`** (today only `.xlsx`/`.csv`).

---

## Decisions (locked during brainstorming)

- **Client-orchestrated, two-phase.** Phase A imports the student rows (as today,
  extended to 4 formats). Phase B attaches photos per student, driven by the browser
  using the student ids returned from import. Avoids a 100MB–1GB single upload and heavy
  server memory.
- **Formats:** data import accepts `.xlsx/.xls/.ods/.csv`. **Embedded** photos are
  extracted from **`.xlsx` only** (ExcelJS). A **`Photo` link column** works in **all**
  formats (server fetches the URL into our bucket). Otherwise photos are added per-student
  later.
- **Never store raw links.** Links are fetched server-side at import, stored in our
  bucket, and skipped-with-warning if inaccessible.
- **New frontend dependency:** SheetJS community `xlsx` for multi-format row parsing.
- **File-size cap:** raised to **50MB** for `.xlsx`-with-embedded-images; row cap stays
  **500**. Processing stays client-side, so the server never receives the whole file.
- Photos are **best-effort**: a student is created even if their photo fails; photos
  never block or roll back the data import.

---

## Architecture & Flow

### Phase A — data import (extended)

1. Browser parses the uploaded file into rows via **SheetJS** (`xlsx` community build),
   which reads `.xlsx/.xls/.ods/.csv` uniformly.
2. If the file is `.xlsx`, it is **also** loaded with **ExcelJS** to extract embedded
   images and their cell anchors (SheetJS does not expose images).
3. Rows → `POST /students/import/preview` (unchanged) → `POST /students/import/confirm`.
4. **`confirmImport` returns a new `insertedStudents: [{admissionNo, studentId}]`** array
   for successfully-created rows.

### Phase B — photo attach (new, best-effort)

For each imported student that has a staged photo (matched by `AdmissionNo → studentId`),
the browser, at a small concurrency (**4 at a time**), calls:

- **Embedded image** (`.xlsx`): canvas-resize to ≤512px (reuse AddStudent's resize) →
  `POST /students/{id}/photo` (existing multipart endpoint; server also resizes + stores).
- **Photo link** (any format): `POST /students/{id}/photo-from-url {url}` → **server**
  fetches, resizes, stores. (The browser cannot fetch cross-origin, and server-fetch is
  where we enforce access control + skip-on-failure.)

A **"Uploading photos X/Y"** progress bar runs during Phase B, followed by a **photo
report**: N attached, M skipped with per-student reasons.

---

## Matching photo → student

A single **`Photo`** column carries either kind:

- **Embedded image** (`.xlsx`): the picture's **top-left anchors into the `Photo` column
  cell of the student's row**. `worksheet.getImages()` yields `{imageId, range:{tl:{row,
  col}}}` with **0-indexed** anchors; map an image to a data row by `tl.row` (accounting
  for the header row), accepting only images whose `tl.col` matches the `Photo` column
  (ignore logos/stray images elsewhere). Exact row/column index arithmetic is pinned in
  the implementation plan.
- **Link** (any format): the `Photo` cell text is an `http(s)` URL.
- **Neither**: no photo.

**`AdmissionNo`** (already unique per school) is the join key: client builds `row →
{embedded bytes | url}`, and after import maps `AdmissionNo → studentId` from confirm's
response to target each photo.

---

## Backend changes (`school-core-service`)

### a) `confirmImport` returns an id map

Add `insertedStudents: [{admissionNo, studentId}]` to the confirm result (and the
job-status result if the import runs as a job), collected from the per-row inserts the
importer already performs. Additive — existing fields unchanged.

### b) New endpoint `POST /api/v1/students/{id}/photo-from-url`

Body: `{ "url": "https://…" }`. Behavior:

- **Auth/scope:** resolve the student's school; enforce `TenantScope` (own-school only,
  superadmin bypass); require the internal `student:write` route token — identical pattern
  to the multipart `POST /students/{id}/photo`.
- **SSRF-guarded fetch** (security-critical):
  - Scheme must be `http` or `https`.
  - Resolve the host; **reject** loopback/private/link-local ranges and the cloud metadata
    address: `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
    `169.254.0.0/16` (incl. `169.254.169.254`), `::1`, `fc00::/7`, `fe80::/10`.
  - Follow at most **3 redirects**, re-validating the host on each hop.
  - **Connect + read timeout 5s**; **max download 2MB** (`student.photo.max-bytes`);
    require an **image `Content-Type`** (`image/jpeg|png|webp`).
- **Success:** `StudentPhotoStorage.upload(schoolId, studentId, bytes, contentType)` →
  set `photo_url` → return `{ photoUrl: <display url> }`.
- **Failure:** return **422** with a machine-usable `reason`
  (`unreachable | not_an_image | too_large | blocked_host | timeout | invalid_url`); the
  student row is untouched (data already imported).

---

## Frontend changes (`frontend`, BulkImportPanel)

- **Parsing:** replace ExcelJS-only-xlsx + hand-rolled CSV with **SheetJS** for rows
  across all four formats; keep **ExcelJS** for `.xlsx` embedded-image extraction only.
- **Accept list:** `.xlsx, .xls, .ods, .csv`; drop-zone copy lists all four.
- **Photo staging:** during parse, build a per-row photo descriptor:
  `{ kind: 'embedded', bytes, contentType }` (xlsx, image anchored to the `Photo` column
  of that row), `{ kind: 'link', url }` (Photo cell is an http(s) URL), or none.
- **Phase B loop:** after confirm, using `AdmissionNo → studentId`, process staged photos
  at concurrency 4:
  - embedded → canvas-resize ≤512px → `POST /students/{id}/photo` (multipart).
  - link → `POST /students/{id}/photo-from-url {url}`.
  - Render **"Uploading photos X/Y"** and, on completion, a **photo report** (attached
    count + per-student skip reasons from the endpoint's `reason`).
- **Format table:** add a `Photo` row — "embedded image in `.xlsx`, or a public image link
  in any format — optional". Mark optional.

---

## Limits & failure handling

- Per-photo size cap **2MB** (`student.photo.max-bytes`); larger embedded images are
  downscaled client-side before upload; oversized links rejected server-side (422).
- File-size cap **50MB** (was 5MB); row cap **500** (unchanged).
- Data import is **authoritative and independent**: a student is created even if the photo
  fails; photos never block or roll back import.
- Idempotent-friendly: re-attaching a photo replaces it; content-addressed storage dedups
  identical bytes.

---

## Testing

**Backend (`school-core-service`):**

- `photo-from-url` happy path stores + returns a display URL (Testcontainers + a stubbed/
  local image source).
- SSRF guards reject: metadata IP `169.254.169.254`, private ranges, `file:`/non-http
  scheme, non-image content-type, oversize body, timeout → **422** with the right `reason`
  and **no** `photo_url` mutation.
- Cross-tenant `{id}` → 403; non-admin caller → 403 (mirrors the multipart photo
  endpoint's authorization).
- `confirmImport` returns `insertedStudents: [{admissionNo, studentId}]` for inserted rows.

**Frontend (Vitest):**

- SheetJS parses each of `.xlsx/.xls/.ods/.csv` into the same row shape.
- Embedded-image extraction maps an image to the correct row by `tl.row`/`tl.col`.
- Phase B calls `POST /students/{id}/photo` for embedded and
  `POST /students/{id}/photo-from-url` for links, at the right ids.
- Progress renders X/Y; a failing photo appears in the skip report and does **not** fail
  the data import.

---

## Out of Scope (YAGNI)

- Embedded-image extraction from `.ods`/`.xls` (link column covers those formats).
- Server-side receipt of the whole file (client-orchestrated per-student avoids it).
- Re-fetching/refreshing link photos later (fetched once at import).
- Bulk photo *update* for existing students as a distinct flow (re-import attaches/replaces).
