# Student Profile And Photo Verification

Last updated: 2026-08-04.

## Scope

Schools need a simple way to verify student profile data and student photos after student records and photo imports are available. The flow should stay minimal for school users, but it must still protect against wrong-student photos, stale profile fields, weak audit history, and parent-facing data exposure.

This document is based on the current codebase plus external privacy, accessibility, and service design references. It is a product and architecture proposal; it does not claim that the runtime implementation already exists beyond the current review-campaign and photo-import primitives.

## Current Project Fit

The project already has most of the base architecture required for this feature:

- `school-core-service` owns the `student` schema, including `students`, `student_review_campaigns`, `student_review_items`, and photo metadata.
- Review campaigns already support `ID_CARD_DETAILS` and `FULL_NAME_VERIFICATION`.
- Review items already contain field-level flags for photo, full name, admission number, class/section, roll number, father name, father contact, address, and blood group.
- The gateway already routes `/api/v1/students/**`, `/api/v1/student-review-items/**`, and `/api/v1/student-photo-imports/**` to school-core.
- The frontend already has `StudentReviewDrawer` with ID-card and full-name tabs, and `StudentPhotoAvatar` resolves protected `/students/{id}/photo/content` references through authenticated API calls.
- Student photo import already supports controlled Google Drive intake, import batches, row review, crop focus, applied/failed counts, and result export.

The proposed implementation should reuse `student_review_campaigns` and `student_review_items`; it should not create a separate verification subsystem.

## External Findings

- FERPA guidance from the U.S. Student Privacy Policy Office treats student photos/videos as potentially protected education records, and also explains that photos may be handled as directory information only under the school's directory-information notice and opt-out process. Product implication: photo verification should be school-scoped, access-controlled, and audit-backed; public URLs should stay avoided.
- FTC COPPA guidance for schools and ed-tech operators emphasizes limited educational use and appropriate consent/authorization paths for children under 13. Product implication: a parent correction link should collect only what is needed and should avoid account-free broad data exposure.
- W3C WCAG 2.2 requires clear labels/instructions, error identification, status messaging, and keyboard-accessible controls. Product implication: verification actions should use native checkboxes/buttons, visible error text, and non-color-only statuses.
- GOV.UK service design uses a "check answers" pattern before submission. Product implication: profile verification should show the current value, correction value, reviewer, and final submit/lock step before marking a campaign complete.
- NIST SP 800-63A separates evidence validation from identity verification. Product implication: this feature should be framed as school-record verification, not biometric identity proofing. Do not introduce face-recognition matching unless there is an explicit legal, consent, and risk decision.
- India's Digital Personal Data Protection Act introduces consent and child-data obligations. Product implication: for India-facing school workflows, avoid unnecessary sensitive identifiers in the verification UI and keep parent/student-facing data minimal.

Reference links:

- https://studentprivacy.ed.gov/faq/faqs-photos-and-videos-under-ferpa
- https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions
- https://www.w3.org/TR/WCAG22/
- https://design-system.service.gov.uk/patterns/check-answers/
- https://pages.nist.gov/800-63-4/sp800-63a.html
- https://www.indiacode.nic.in/handle/123456789/22037

## Minimal MVP

Keep verification inside the existing `Students` view. Do not add a fourth `StudentModuleTabs` tab unless the workflow becomes large enough later.

The MVP should add two small actions to the student directory toolbar:

- `Verify profiles`: verifies core profile fields needed for ID cards, attendance, fees, and parent communication.
- `Verify photos`: verifies that the imported/uploaded photo is usable and belongs to the student record.

Those actions open a compact review drawer or modal seeded from the same Students filters. The reviewer should feel they are still working from the student directory, not entering a separate product area.

## Product Lineage

Recommended lineage:

```text
ERP workspace
  -> Students module
     -> Students tab
        -> Student directory toolbar
           -> Verify profiles
              -> Profile review campaign
              -> student_review_campaigns.review_type = PROFILE_VERIFICATION
           -> Verify photos
              -> Photo review campaign
              -> student_review_campaigns.review_type = PHOTO_VERIFICATION
```

Operational lineage:

```text
Student record created/imported
  -> Student photo uploaded or imported from Drive
  -> School verifies profile fields and photo
  -> Corrections are handled in the student record/photo import flow
  -> Verified students become ready for ID-card printing and downstream use
```

Photo import should remain a source/intake workflow. Verification is the school approval layer after intake.

The smallest useful workflow:

1. Open `Students`.
2. Use the existing class, section, mode, and search filters.
3. Click `Verify profiles` or `Verify photos`.
4. Start or resume the campaign for that filtered scope.
5. Review one student at a time in a compact pane.
6. Mark `Verified` or `Needs correction`.
7. Require a reason/note when `Needs correction` is selected.
8. Complete campaign only when every item is `COMPLETED` or intentionally closed as `NEEDS_CORRECTION`.

The school user should not need to understand batches, object keys, import internals, or service topology.

## Required Data Behavior

Reuse existing columns for MVP:

- `student_review_campaigns.review_type`
- `student_review_campaigns.status`
- `student_review_items.status`
- `verified_photo`
- `verified_full_name`
- `verified_admission_no`
- `verified_class_section`
- `verified_roll_no`
- `verified_father_name`
- `verified_father_contact`
- `verified_address`
- `verified_blood_group`
- `correction_requested`
- `correction_notes`
- `completed_at`

Recommended forward-only additions when implementation starts:

- `student_review_items.photo_correction_reason VARCHAR(50)` for `WRONG_STUDENT`, `LOW_QUALITY`, `NOT_CENTERED`, `MISSING_UNIFORM`, `OTHER`.
- `student_review_items.profile_correction_reason VARCHAR(50)` for `NAME`, `ADMISSION_NO`, `CLASS_SECTION`, `CONTACT`, `ADDRESS`, `OTHER`.
- `student_review_items.reviewed_by BIGINT` and `reviewed_at TIMESTAMPTZ` so the audit is visible without depending only on outbox history.
- `student_review_items.photo_source VARCHAR(30)` for `MANUAL_UPLOAD`, `DRIVE_IMPORT`, `BULK_IMPORT_URL`, `UNKNOWN`.
- `student_review_items.last_photo_version VARCHAR(80)` to detect when a photo changed after verification and automatically reopen photo verification.

These should be added through new Flyway migrations only; existing applied migrations must not be edited.

## API Shape

Prefer extending the existing student-review endpoints instead of adding a new service:

- `GET /api/v1/students/reviews/profile-photo/status`
- `POST /api/v1/students/reviews/profile-photo/initiate`
- `GET /api/v1/students/review-campaigns/{campaignId}/items`
- `PUT /api/v1/student-review-items/{itemId}`
- `POST /api/v1/students/review-campaigns/{campaignId}/complete`

Use separate campaign types for this product shape:

- `PROFILE_VERIFICATION`
- `PHOTO_VERIFICATION`

This keeps the two toolbar actions simple and avoids forcing profile and photo review to finish at the same pace.

## UI Decisions

- Put the entry point in the existing Students toolbar, next to existing import/add actions.
- Use exactly two visible verification actions: `Verify profiles` and `Verify photos`.
- The review surface should inherit the current Students filters by default.
- Show the student photo near the fields being verified; photo review without a visible portrait is not acceptable.
- Use plain statuses: `Pending`, `Verified`, `Needs correction`.
- Use a single high-emphasis action: `Save review`.
- Avoid bulk "verify all photos" in MVP. Bulk actions can be added later only after exceptions and audit are strong.
- Make correction reasons structured, then allow optional notes.
- Keep parent-facing correction links out of MVP unless notification delivery is production-proven and the privacy copy is approved.

## Gaps In Current Flow

- The existing `StudentReviewDrawer` has ID-card and full-name tabs, but it does not give schools a dedicated profile/photo verification workspace.
- The current ID-card checklist includes `verifiedPhoto`, but it does not show a photo comparison/detail pane in the reviewed code path.
- Current review item fields track booleans, but not structured reason codes, reviewer identity, or the photo version verified.
- Existing photo caching in `StudentPhotoAvatar` is capped at 300 object URLs; large review sessions need predictable refresh/version behavior.
- Photo import has batch status and row-level errors, but imported photo quality and post-import school approval are separate concerns and are not yet modeled as one handoff.
- The notification architecture has a verified caveat: real provider delivery is not proven enabled in prod documentation. Parent correction links should wait until that is closed.
- The current frontend review drawer contains inline styles and mojibake comments; implementation should clean the touched code while avoiding unrelated rewrites.

## Improvements And New Features

Priority 1:

- Add `Verify profiles` and `Verify photos` actions inside the existing Students view.
- Add profile/photo campaign status cards to the command center lifecycle area.
- Add selected-student detail with protected photo preview, profile fields, structured correction reasons, and save action.
- Reopen photo verification automatically when `students.photo_url` changes after verification.
- Add campaign completion guard and clear "remaining to review" count.

Priority 2:

- Add assign-to-teacher and class-section workload splits using the existing `assigned_to_user_id`.
- Add correction export CSV for office staff.
- Add photo quality checks for file type, size, dimensions, crop center, and optionally face-present detection. Do not add biometric identity matching by default.
- Add audit timeline per review item: created, assigned, verified, correction requested, photo replaced, completed.

Priority 3:

- Parent correction request flow with expiring token, limited fields, one-time submission, and school approval.
- Reminder automation once real notification provider delivery is verified.
- Verification SLA dashboard by class/section.
- ID-card print-readiness gate: do not allow ID-card export for unverified students unless a privileged user overrides with a reason.

## Mockups

Separate mockups are available at:

- `docs/mockups/student-verification-mvp.html` for the reduced school verification flow.
- `docs/mockups/student-verification-future.html` for a small later-improvements view.
