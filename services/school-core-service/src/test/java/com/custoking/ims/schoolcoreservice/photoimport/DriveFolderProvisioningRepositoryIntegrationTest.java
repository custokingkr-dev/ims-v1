package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.SchoolDriveScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class DriveFolderProvisioningRepositoryIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbc;
    private static DriveFolderProvisioningRepository repository;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "owner", "owner"));
        jdbc.sql("CREATE SCHEMA student").update();
        jdbc.sql("""
                CREATE TABLE student.photo_import_drive_folders (
                    school_id BIGINT NOT NULL,
                    school_uid UUID NOT NULL,
                    academic_year_id VARCHAR(255) NOT NULL,
                    root_folder_id VARCHAR(255) NOT NULL,
                    school_folder_id VARCHAR(255),
                    academic_year_folder_id VARCHAR(255),
                    intake_folder_id VARCHAR(255),
                    intake_folder_name VARCHAR(255),
                    intake_folder_url VARCHAR(1000),
                    status VARCHAR(24) NOT NULL,
                    last_error TEXT,
                    provisioned_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    version BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (school_id, academic_year_id)
                )
                """).update();
        repository = new DriveFolderProvisioningRepository(jdbc);
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void claimIsExclusiveAndCompletionIsFencedByVersion() {
        SchoolDriveScope scope = new SchoolDriveScope(
                7L,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "GVS",
                "ay_2026_27",
                "2026-27");
        var folders = new GoogleDrivePhotoImportClient.ProvisionedFolders(
                "school-folder",
                "year-folder",
                "intake-folder",
                "Student Photo Intake",
                "https://drive.google.com/drive/folders/intake-folder");

        var first = repository.claimProvisioning(scope, "root-folder", false).orElseThrow();
        assertThat(repository.claimProvisioning(scope, "root-folder", false)).isEmpty();

        var ready = repository.markReady(scope, "root-folder", first.version(), folders);
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(repository.claimProvisioning(scope, "root-folder", false)).isEmpty();

        var repair = repository.claimProvisioning(scope, "root-folder", true).orElseThrow();
        assertThat(repair.version()).isGreaterThan(ready.version());

        var staleCompletion = repository.markReady(scope, "root-folder", first.version(), folders);
        assertThat(staleCompletion.status()).isEqualTo("PROVISIONING");
        assertThat(staleCompletion.version()).isEqualTo(repair.version());

        var repaired = repository.markReady(scope, "root-folder", repair.version(), folders);
        assertThat(repaired.status()).isEqualTo("READY");
        assertThat(repaired.intakeFolderId()).isEqualTo("intake-folder");

        var rootChanged = repository.claimProvisioning(scope, "new-root-folder", false).orElseThrow();
        assertThat(rootChanged.rootFolderId()).isEqualTo("new-root-folder");
        assertThat(rootChanged.status()).isEqualTo("PROVISIONING");
        assertThat(rootChanged.schoolFolderId()).isNull();
        assertThat(rootChanged.academicYearFolderId()).isNull();
        assertThat(rootChanged.intakeFolderId()).isNull();
        assertThat(rootChanged.intakeFolderUrl()).isNull();
    }

    @Test
    void abandonedClaimCanBeRecoveredAfterLeaseExpires() {
        SchoolDriveScope scope = new SchoolDriveScope(
                8L,
                "22222222-2222-4222-8222-222222222222",
                "Riverdale School",
                "RVS",
                "ay_2026_27",
                "2026-27");
        var abandoned = repository.claimProvisioning(scope, "root-folder", false).orElseThrow();
        jdbc.sql("""
                UPDATE student.photo_import_drive_folders
                SET updated_at = now() - interval '6 minutes'
                WHERE school_id = :schoolId AND academic_year_id = :academicYearId
                """)
                .param("schoolId", scope.schoolId())
                .param("academicYearId", scope.academicYearId())
                .update();

        var recovered = repository.claimProvisioning(scope, "root-folder", false).orElseThrow();

        assertThat(recovered.status()).isEqualTo("PROVISIONING");
        assertThat(recovered.version()).isGreaterThan(abandoned.version());
    }
}
