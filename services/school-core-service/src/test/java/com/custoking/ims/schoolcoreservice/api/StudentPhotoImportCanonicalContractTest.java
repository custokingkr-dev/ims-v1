package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.contracts.OpenApiContractAssertions;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.*;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.*;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class StudentPhotoImportCanonicalContractTest {
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ROW = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ROOT = "/api/v1/student-photo-imports";
    private static final OpenApiContractAssertions CONTRACT = new OpenApiContractAssertions("photoImportClient.v1.openapi.json");
    private final PhotoImportService service = mock(PhotoImportService.class);
    private final MockMvc mvc = mvc(service);
    @AfterEach void clearTenant() { TenantContext.clear(); }
    private MockMvc mvc(PhotoImportService owner) {
        return MockMvcBuilders.standaloneSetup(new StudentPhotoImportController(owner, "tok"))
                .setControllerAdvice(new ValidationExceptionHandler()).addFilters(new TenantContextFilter()).build();
    }
    private record Operation(String path, String method, String body) {
        MockHttpServletRequestBuilder request() {
            String url = ROOT + path.replace("{id}", ID.toString()).replace("{batchId}", ID.toString())
                    .replace("{rowId}", ROW.toString()).replace("{schoolId}", "7");
            if (path.isEmpty() && method.equals("get")) url += "?schoolId=7";
            var request = method.equals("get") ? get(url) : post(url);
            return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
    }
    private static List<Operation> operations() {
        return List.of(new Operation("/context", "get", null), new Operation("", "get", null),
                new Operation("", "post", "{\"schoolId\":7,\"academicYearId\":\"ay\"}"),
                new Operation("/folders/{schoolId}/provision", "post", null), new Operation("/{id}", "get", null),
                new Operation("/{id}/scan", "post", null), new Operation("/{id}/freeze", "post", null),
                new Operation("/{id}/cancel", "post", null), new Operation("/{id}/execute", "post", null),
                new Operation("/{id}/access-revoked", "post", null),
                new Operation("/{batchId}/rows/{rowId}", "post", "{\"admissionNo\":\"ADM-1\",\"cropX\":0.5}"),
                new Operation("/{id}/recover", "post", "{\"rowIds\":[\"" + ROW + "\"]}"),
                new Operation("/{id}/recovery-progress", "get", null),
                new Operation("/{batchId}/rows/{rowId}/preview", "get", null), new Operation("/{id}/result", "get", null));
    }
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder request, String role, String permission) {
        return request.header("X-Student-Service-Token", "tok").header("X-Authenticated-Role", role)
                .header("X-Authenticated-User-Id", "42").header("X-Authenticated-Operator-Schools", "7")
                .header("X-Authenticated-Permissions", permission);
    }
    private static Batch batch() {
        var now = OffsetDateTime.parse("2026-10-12T00:00:00Z");
        return new Batch(ID, 7L, ID.toString(), "School", "ay", "2026-27", "folder", null, null, null,
                "FROZEN", null, 1, 0, 1, 0, 0, 0, 42L, null, now, null, null, null, now, null, null, 0L);
    }
    private static ImportRow row() {
        return new ImportRow(ROW, ID, 7L, 2, "ADM-1", "Student", "I", "A", null, null, null, null,
                "HELD", "Missing original", null, null, null, null, 0.5, 0.5, false, null, null);
    }
    @Test void allJsonOperationsSerializeCompleteNullableContractsAndPrivateHeaders() throws Exception {
        var access = new AccessState(null, null, false);
        var progress = new RecoveryProgress(ID, 7L, 1, 1, 0, 1, 0, 0, 0, 100, false, null);
        var context = Map.of("driveConfigured", true, "managedDriveConfigured", false,
                "schools", List.of(new PhotoImportService.SchoolPhotoImportContext(7L, ID.toString(), "School", "S", "ay", "2026-27", "NOT_PROVISIONED", null, null, null, null)),
                "mappingColumns", List.of("AdmissionNo"), "mappingFileFormats", List.of("CSV"), "mappingRowLimit", 10000,
                "imageFileLimit", "20 MB", "fileNameRule", "DSC123.jpg");
        when(service.context()).thenReturn(context);
        when(service.list(7L)).thenReturn(List.of(batch()));
        when(service.create(7L, "ay", null)).thenReturn(batch());
        when(service.provision(7L)).thenReturn(new DriveFolderProvisioningService.ProvisioningResult(7L, ID.toString(), "School", "S", "ay", "2026-27", "FAILED", null, null, null, "not configured"));
        when(service.detail(ID)).thenReturn(Map.of("batch", batch(), "rows", List.of(row()), "access", access, "recoveryProgress", progress));
        when(service.scan(ID)).thenReturn(batch()); when(service.freeze(ID)).thenReturn(batch());
        when(service.cancel(ID)).thenReturn(batch()); when(service.execute(ID)).thenReturn(batch());
        when(service.markAccessRevoked(ID)).thenReturn(access);
        when(service.updateRow(eq(ID), eq(ROW), any())).thenReturn(Map.of("batch", batch(), "row", row()));
        when(service.recoveryProgress(ID)).thenReturn(progress);
        when(service.recoverAppliedRows(ID, List.of(ROW))).thenReturn(new PhotoImportService.RecoveryBatchResult(ID, 7L, 1, 0, 0, 0, 1, 0,
                List.of(new RecoveryResult(ROW, "PROTECTED", null, "Newer photo retained")), progress));
        for (var operation : operations().subList(0, 13)) {
            var result = mvc.perform(operation.request().header("X-Student-Service-Token", "tok"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("Vary", "Authorization")).andReturn();
            CONTRACT.assertEndpointResponse(ROOT + operation.path(), operation.method(), result.getResponse().getContentAsString());
        }
    }
    @Test void binaryPreviewsAndCsvAreNoStoreWithExactBytesAndAttachmentHeaders() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        when(service.preview(ID, ROW)).thenReturn(new PhotoImportService.Preview(jpeg, "image/jpeg"));
        when(service.resultCsv(ID)).thenReturn("admissionNo,status\r\nADM-1,HELD\r\n");
        mvc.perform(operations().get(13).request().header("X-Student-Service-Token", "tok"))
                .andExpect(status().isOk()).andExpect(content().bytes(jpeg)).andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store"));
        var result = mvc.perform(operations().get(14).request().header("X-Student-Service-Token", "tok"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=student-photo-import-" + ID + ".csv")).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("admissionNo,status\r\nADM-1,HELD\r\n");
    }
    @Test void invalidPeerTokenRejectsAllOperationsBeforeAnyOwnerCalls() throws Exception {
        for (var operation : operations()) mvc.perform(operation.request().header("X-Student-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    private record Owner(PhotoImportRepository repo, GoogleDrivePhotoImportClient drive, PhotoImportWorkbookParser parser,
                         StudentPhotoStorage storage, DriveFolderProvisioningService provisioning, MockMvc mvc) {}
    private Owner owner() {
        var repo = mock(PhotoImportRepository.class); var drive = mock(GoogleDrivePhotoImportClient.class);
        var parser = mock(PhotoImportWorkbookParser.class); var storage = mock(StudentPhotoStorage.class);
        var provisioning = mock(DriveFolderProvisioningService.class);
        return new Owner(repo, drive, parser, storage, provisioning, mvc(new PhotoImportService(repo, drive, parser, storage, provisioning)));
    }
    @Test void missingPermissionWrongRoleAndAbsentPrincipalRejectEveryOperationBeforeRepositories() throws Exception {
        var owner = owner();
        for (var operation : operations()) {
            owner.mvc().perform(actor(operation.request(), "OPERATIONS", "student:read")).andExpect(status().isForbidden());
            owner.mvc().perform(actor(operation.request(), "ADMIN", "student:photo-import")).andExpect(status().isForbidden());
            owner.mvc().perform(operation.request().header("X-Student-Service-Token", "tok")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(owner.repo(), owner.drive(), owner.parser(), owner.storage(), owner.provisioning());
    }
    @Test void foreignSchoolsRejectReadsWritesDownloadsAndRecoveryBeforeExternalOrBusinessActions() throws Exception {
        var owner = owner(); when(owner.repo().batchSchoolId(ID)).thenReturn(9L);
        for (var operation : operations().subList(4, 15)) owner.mvc().perform(actor(operation.request(), "OPERATIONS", "student:photo-import")).andExpect(status().isForbidden());
        for (var request : List.of(get(ROOT + "?schoolId=9"), post(ROOT + "/folders/9/provision"),
                post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":9,\"academicYearId\":\"ay\"}"))) {
            owner.mvc().perform(actor(request, "OPERATIONS", "student:photo-import")).andExpect(status().isForbidden());
        }
        verify(owner.repo(), times(11)).batchSchoolId(ID); verifyNoMoreInteractions(ignoreStubs(owner.repo()));
        verifyNoInteractions(owner.drive(), owner.parser(), owner.storage(), owner.provisioning());
    }
    @Test void disabledModuleRejectsBatchActionsAndProvisioningWithoutExternalWrites() throws Exception {
        var owner = owner(); when(owner.repo().batchSchoolId(ID)).thenReturn(7L);
        for (var operation : operations().subList(4, 15)) owner.mvc().perform(actor(operation.request(), "OPERATIONS", "student:photo-import")).andExpect(status().isForbidden());
        owner.mvc().perform(actor(post(ROOT + "/folders/7/provision"), "OPERATIONS", "student:photo-import")).andExpect(status().isForbidden());
        verify(owner.repo(), times(12)).studentsModuleEnabled(7L);
        verifyNoInteractions(owner.drive(), owner.parser(), owner.storage(), owner.provisioning());
    }
    @Test void invalidBodiesAndIdentifiersFailBeforeOwnerMutation() throws Exception {
        for (var body : List.of("{\"rowIds\":[]}", "{\"rowIds\":[null]}", "{\"rowIds\":[\"bad-uuid\"]}"))
            mvc.perform(post(ROOT + "/" + ID + "/recover").header("X-Student-Service-Token", "tok").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT).header("X-Student-Service-Token", "tok").contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":7,\"academicYearId\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/" + ID + "/rows/" + ROW).header("X-Student-Service-Token", "tok").contentType(MediaType.APPLICATION_JSON).content("{\"cropX\":1.1}")).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/bad-uuid").header("X-Student-Service-Token", "tok")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
