INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'SchoolUpserted:'||id, 'school.upserted.v1', 'School', id::text, id,
       jsonb_build_object('id',id,'name',name,'shortCode',short_code,'city',city,'state',state,'active',active)
FROM tenant_school.schools;

INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'SchoolSectionUpserted:'||ss.id, 'school-section.upserted.v1', 'SchoolSection', ss.id, ss.school_id,
       jsonb_build_object('id',ss.id,'name',ss.name,'schoolId',ss.school_id,'classId',ss.school_class_id,
                          'className',sc.name,'active',ss.active,'teacherName',ss.teacher_name)
FROM tenant_school.school_sections ss
LEFT JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id;

INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'AcademicYearUpserted:'||id, 'academic-year.upserted.v1', 'AcademicYear', id, NULL,
       jsonb_build_object('id',id,'label',label,'active',active)
FROM tenant_school.academic_years;
