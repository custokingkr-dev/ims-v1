# Student Photo Upload — Design

## Problem

The Add-Student UI uploads a face photo as `multipart/form-data` to `POST /api/v1/students/{id}/photo`, but that endpoint expects JSON `{photoUrl}` (a pre-hosted URL) and there is **no file-storage backend** — so uploads fail (415 → surfaced as 502). Student photos are faces of **minors** (sensitive PII under DPDP/FERPA) and must be access-controlled.

## Constraints

- ~500–600 photos/school, many schools (tens of thousands of objects total).
- Low storage cost; low load latency in list/detail views.
- Private (never public); tenant-isolated access.
- Frontend already sends `multipart/form-data` (field `file`, ≤2 MB, JPG/PNG/WEBP) and renders `student.photoUrl` directly as `<img src>`.

## Design

**Storage — Google Cloud Storage, private bucket.** Not the DB (bloats Cloud SQL). Not public (PII). One regional bucket per env: `custoking-student-photos-{env}` (`asia-south2`), uniform bucket-level access + public-access-prevention. Object key is content-addressed + tenant-scoped: `students/{schoolId}/{studentId}/{sha256}.jpg`.

**Cost + latency lever — resize on upload.** Server-side downscale + center-crop to a **512×512 JPEG (~q0.82, ~40–60 KB)** with Thumbnailator, a ~40× reduction from 2 MB. Objects are written with `Cache-Control: public, max-age=1y, immutable` (content-addressed → never change) so browsers cache them. At 50 KB × 60k photos ≈ 3 GB → **~$0.06/mo** GCS Standard; egress stays low from small, cached images.

**Serving — short-TTL V4 signed URLs.** `<img>` can't send Authorization headers, so private objects are served via signed URLs (the URL is the credential). Read endpoints (list, detail, create-return) convert the stored object key → a fresh signed URL (default 60-min TTL) in the response. Signed URLs are only ever issued by **tenant-scoped** read endpoints, so a school-A user never receives a URL for a school-B photo. Cloud Run SAs have no local private key, so URLs are signed via **IAM SignBlob** using `ImpersonatedCredentials` self-impersonation (runtime SA has `iam.serviceAccountTokenCreator` on itself). **Cloud CDN is deferred** — it adds pricing complexity and mainly helps global repeat traffic; a regional ERP with small cached images doesn't need it yet.

## Components (school-core-service)

1. **`StudentPhotoStorage`** — `upload(schoolId, studentId, bytes, contentType) → objectKey` (validate type/size → resize → write to GCS) and `toDisplayUrl(stored) → String` (null→null; `http…`→as-is for legacy/external URLs; else a V4 signed URL). Degrades gracefully when no bucket is configured (local/tests): `toDisplayUrl` returns the stored value unchanged and `upload` fails with a clear 503.
2. **`StudentReadController.attachPhoto`** — accept `@RequestParam("file") MultipartFile`, validate content-type (jpeg/png/webp) and size (≤2 MB), call `StudentPhotoStorage.upload`, persist the returned object key, return the (signed) student detail.
3. **`StudentReadRepository`** — `attachPhoto` stores the object key; the three photo row-mappers wrap `photo_url` with `StudentPhotoStorage.toDisplayUrl(...)`.
4. **Config** (`application.yml`, env-driven): `student.photo.bucket`, `.signed-url-ttl-minutes` (60), `.max-bytes` (2 MiB), `.dimension` (512); Spring `servlet.multipart.max-file-size=2MB`.
5. **Deps**: `com.google.cloud:google-cloud-storage` (via libraries-bom, matching billing's Pub/Sub setup) + `net.coobird:thumbnailator`.
6. **Infra / cloudbuild**: bucket per env + runtime SA `storage.objectAdmin` on it + self `serviceAccountTokenCreator`; pass `STUDENT_PHOTO_BUCKET=custoking-student-photos-${_ENV}` to school-core. Added to the greenfield runbook.

## Validation

- Upload a JPEG/PNG → stored resized in GCS; student read returns a working signed `photoUrl` that loads in a browser.
- Oversized/invalid type → 400 (not 502/500).
- Tenant isolation: a school-A admin's list never returns school-B photo URLs (existing tenant scoping on the read path).
- No bucket configured → service still starts; reads return legacy string URLs unchanged.

## Non-goals (YAGNI)

Cloud CDN; direct browser→GCS signed-PUT uploads; multiple stored sizes (single 512² covers list+detail); WebP output (JPEG is smaller-enough and universally supported); background re-encoding of legacy URLs.
