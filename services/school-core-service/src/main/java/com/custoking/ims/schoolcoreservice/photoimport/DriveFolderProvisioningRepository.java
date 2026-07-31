package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.persistence.AcademicCalendarAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class DriveFolderProvisioningRepository {
    private final JdbcClient jdbc;

    public DriveFolderProvisioningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public SchoolDriveScope currentScope(long schoolId) {
        bypassRls();
        SchoolBase school = jdbc.sql("""
                        SELECT id, school_uid::text AS school_uid, name, short_code
                        FROM tenant_school.schools
                        WHERE id = :schoolId AND active = true
                        """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new SchoolBase(
                        rs.getLong("id"),
                        rs.getString("school_uid"),
                        rs.getString("name"),
                        rs.getString("short_code")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Active school not found"));
        var year = AcademicCalendarAccess.currentAcademicYear(jdbc, schoolId);
        return new SchoolDriveScope(
                school.id(),
                school.schoolUid(),
                school.name(),
                school.shortCode(),
                year.id(),
                year.label());
    }

    @Transactional(readOnly = true)
    public Optional<DriveFolderBinding> find(long schoolId, String academicYearId) {
        bypassRls();
        return jdbc.sql("""
                        SELECT school_id, school_uid::text AS school_uid, academic_year_id,
                               root_folder_id, school_folder_id, academic_year_folder_id,
                               intake_folder_id, intake_folder_name, intake_folder_url,
                               status, last_error, provisioned_at, updated_at, version
                        FROM student.photo_import_drive_folders
                        WHERE school_id = :schoolId AND academic_year_id = :academicYearId
                        """)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .query(this::mapBinding)
                .optional();
    }

    @Transactional
    public Optional<DriveFolderBinding> claimProvisioning(
            SchoolDriveScope scope,
            String rootFolderId,
            boolean replaceReadyBinding) {
        bypassRls();
        return jdbc.sql("""
                        INSERT INTO student.photo_import_drive_folders
                            (school_id, school_uid, academic_year_id, root_folder_id,
                             status, last_error, updated_at)
                        VALUES
                            (:schoolId, :schoolUid::uuid, :academicYearId, :rootFolderId,
                             'PROVISIONING', NULL, now())
                        ON CONFLICT (school_id, academic_year_id) DO UPDATE
                        SET school_uid = EXCLUDED.school_uid,
                            root_folder_id = EXCLUDED.root_folder_id,
                            status = 'PROVISIONING',
                            last_error = NULL,
                            updated_at = now(),
                            version = student.photo_import_drive_folders.version + 1
                        WHERE student.photo_import_drive_folders.root_folder_id
                                  IS DISTINCT FROM EXCLUDED.root_folder_id
                           OR student.photo_import_drive_folders.status = 'FAILED'
                           OR (student.photo_import_drive_folders.status = 'READY'
                               AND :replaceReadyBinding)
                           OR (student.photo_import_drive_folders.status = 'PROVISIONING'
                               AND student.photo_import_drive_folders.updated_at
                                   < now() - interval '5 minutes')
                        RETURNING school_id, school_uid::text AS school_uid, academic_year_id,
                                  root_folder_id, school_folder_id, academic_year_folder_id,
                                  intake_folder_id, intake_folder_name, intake_folder_url,
                                  status, last_error, provisioned_at, updated_at, version
                        """)
                .param("schoolId", scope.schoolId())
                .param("schoolUid", scope.schoolUid())
                .param("academicYearId", scope.academicYearId())
                .param("rootFolderId", rootFolderId)
                .param("replaceReadyBinding", replaceReadyBinding)
                .query(this::mapBinding)
                .optional();
    }

    @Transactional
    public DriveFolderBinding markReady(
            SchoolDriveScope scope,
            String rootFolderId,
            long claimVersion,
            GoogleDrivePhotoImportClient.ProvisionedFolders folders) {
        bypassRls();
        jdbc.sql("""
                        UPDATE student.photo_import_drive_folders
                        SET school_folder_id = :schoolFolderId,
                            academic_year_folder_id = :academicYearFolderId,
                            intake_folder_id = :intakeFolderId,
                            intake_folder_name = :intakeFolderName,
                            intake_folder_url = :intakeFolderUrl,
                            status = 'READY',
                            last_error = NULL,
                            provisioned_at = now(),
                            updated_at = now(),
                            version = version + 1
                        WHERE school_id = :schoolId
                          AND academic_year_id = :academicYearId
                          AND root_folder_id = :rootFolderId
                          AND status = 'PROVISIONING'
                          AND version = :claimVersion
                        """)
                .param("schoolId", scope.schoolId())
                .param("academicYearId", scope.academicYearId())
                .param("rootFolderId", rootFolderId)
                .param("claimVersion", claimVersion)
                .param("schoolFolderId", folders.schoolFolderId())
                .param("academicYearFolderId", folders.academicYearFolderId())
                .param("intakeFolderId", folders.intakeFolderId())
                .param("intakeFolderName", folders.intakeFolderName())
                .param("intakeFolderUrl", folders.intakeFolderUrl())
                .update();
        return find(scope.schoolId(), scope.academicYearId()).orElseThrow();
    }

    @Transactional
    public DriveFolderBinding markFailed(
            SchoolDriveScope scope,
            String rootFolderId,
            long claimVersion,
            String error) {
        bypassRls();
        jdbc.sql("""
                        UPDATE student.photo_import_drive_folders
                        SET status = 'FAILED',
                            last_error = :error,
                            updated_at = now(),
                            version = version + 1
                        WHERE school_id = :schoolId
                          AND academic_year_id = :academicYearId
                          AND root_folder_id = :rootFolderId
                          AND status = 'PROVISIONING'
                          AND version = :claimVersion
                        """)
                .param("schoolId", scope.schoolId())
                .param("academicYearId", scope.academicYearId())
                .param("rootFolderId", rootFolderId)
                .param("claimVersion", claimVersion)
                .param("error", truncate(error, 1000))
                .update();
        return find(scope.schoolId(), scope.academicYearId()).orElseThrow();
    }

    private DriveFolderBinding mapBinding(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new DriveFolderBinding(
                rs.getLong("school_id"),
                rs.getString("school_uid"),
                rs.getString("academic_year_id"),
                rs.getString("root_folder_id"),
                rs.getString("school_folder_id"),
                rs.getString("academic_year_folder_id"),
                rs.getString("intake_folder_id"),
                rs.getString("intake_folder_name"),
                rs.getString("intake_folder_url"),
                rs.getString("status"),
                rs.getString("last_error"),
                rs.getObject("provisioned_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    private void bypassRls() {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
    }

    private static String truncate(String value, int max) {
        String normalized = value == null || value.isBlank() ? "Drive folder provisioning failed" : value;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private record SchoolBase(long id, String schoolUid, String name, String shortCode) {
    }

    public record SchoolDriveScope(
            long schoolId,
            String schoolUid,
            String schoolName,
            String shortCode,
            String academicYearId,
            String academicYearLabel) {
    }

    public record DriveFolderBinding(
            long schoolId,
            String schoolUid,
            String academicYearId,
            String rootFolderId,
            String schoolFolderId,
            String academicYearFolderId,
            String intakeFolderId,
            String intakeFolderName,
            String intakeFolderUrl,
            String status,
            String lastError,
            OffsetDateTime provisionedAt,
            OffsetDateTime updatedAt,
            long version) {
    }
}
