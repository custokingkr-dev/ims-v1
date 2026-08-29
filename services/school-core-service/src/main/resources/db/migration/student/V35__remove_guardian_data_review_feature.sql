-- Retire the review-only guardian parity workflow. Canonical student records,
-- guardians, relationships, consent evidence, and repair ledgers are untouched.

DROP VIEW IF EXISTS student.guardian_data_review_queue_v1;
DROP VIEW IF EXISTS student.guardian_data_review_queue_v32;
DROP VIEW IF EXISTS student.guardian_data_review_queue_v31;
DROP VIEW IF EXISTS student.guardian_data_review_queue_v30;
DROP VIEW IF EXISTS student.guardian_data_review_queue_v29;

DROP TABLE IF EXISTS student.guardian_data_review_decisions;
