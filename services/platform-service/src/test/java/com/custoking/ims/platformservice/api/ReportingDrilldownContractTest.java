package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportingDrilldownContractTest {
    private final ReportingReadRepository repo = mock(ReportingReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReportingReadController(
            repo, mock(ReportingCommandRepository.class), "tok")).addFilters(new TenantContextFilter()).build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void cleanup() { TenantContext.clear(); }

    private MockHttpServletRequestBuilder scoped(String path) {
        return get("/api/v1/reporting" + path).header("X-Reporting-Service-Token", "tok")
                .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10")
                .header("X-Authenticated-Permissions", "report:read");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dashboard-command-center", "/fee-defaulters", "/low-attendance/sections",
            "/low-attendance/sections/s-1/students", "/vendor-dues", "/reorder-signals"})
    void eachDrilldownRejectsForgedScopeMissingPermissionAndWrongServiceToken(String path) throws Exception {
        mvc.perform(scoped(path).param("schoolId", "99")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reporting" + path).header("X-Reporting-Service-Token", "tok")
                .header("X-Authenticated-Role", "ADMIN").header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reporting" + path).header("X-Reporting-Service-Token", "wrong")
                .header("X-Authenticated-Role", "SUPERADMIN")).andExpect(status().isUnauthorized());
        verifyNoInteractions(repo);
    }

    @Test void allSixSerializedResponsesMatchTheirCanonicalContractAndPreserveFilters() throws Exception {
        Map<String, Object> vendorTotals = row("catalogOrderCount", 1, "catalogOrderTotalPaise", 2500,
                "firefightingCount", 0, "firefightingTotalPaise", 0, "totalDuesPaise", 2500);
        Map<String, Object> dashboard = row("fees", row("defaulterCount", 1, "totalOverdueAmountPaise", 2500, "oldestDueDays", 31),
                "photography", row("eventId", null, "collectedAmount", 0, "pendingAmount", 0, "targetAmount", 0),
                "lifecycle", row("pendingReviewCount", 1, "longAbsenceCount", 0),
                "attendance", row("sectionsBelowThresholdCount", 1, "thresholdPercent", 75),
                "vendorDues", vendorTotals, "reorderSignals", row("alertCount", 1));
        Map<String, Object> fee = row("totalDefaulters", 1, "totalOverdueAmount", 2500, "oldestDueDays", 31,
                "page", 0, "size", 25, "totalElements", 1, "items", List.of(row("studentId", 7,
                        "studentName", "Student", "admissionNo", "A-7", "className", "Class 1", "sectionName", "A",
                        "parentName", null, "parentPhone", null, "dueAmount", 2500, "dueDate", "2026-09-11",
                        "daysOverdue", 31, "lastReminderSentAt", null, "reminderStatus", "NOT_SENT", "paymentStatus", "OVERDUE")));
        Map<String, Object> sections = row("date", "2026-10-12", "thresholdPercent", 75, "sections", List.of(row(
                "sectionId", "s-1", "sectionName", "A", "className", "Class 1", "presentCount", 2,
                "totalEnrolled", 4, "attendancePct", 50, "studentsBelowThreshold", 1)));
        List<Map<String, Object>> students = List.of(row("studentId", 7, "studentName", "Student", "admissionNo", "A-7",
                "className", "Class 1", "sectionName", "A", "fatherName", null, "fatherContact", null,
                "attendancePercent", null, "lastInviteSentAt", null));
        Map<String, Object> vendor = new LinkedHashMap<>(vendorTotals);
        vendor.put("items", List.of(row("sourceType", "CATALOG_ORDER", "id", "ORD-1", "title", "Trophies",
                "category", "TROPHIES", "vendorName", null, "amountPaise", 2500, "status", "APPROVED", "createdAt", null)));
        Map<String, Object> reorder = row("alertCount", 1, "items", List.of(row("category", "TROPHIES",
                "lastOrderDate", "2026-01-01", "daysSinceLastOrder", 284, "avgIntervalDays", null,
                "predictedNextOrderDate", null, "alertLevel", "YELLOW")));
        when(repo.dashboardCommandCenter(10L)).thenReturn(dashboard);
        when(repo.feeDefaulters(10L, "c-1", "s-1", 30, "NOT_SENT", 0, 25)).thenReturn(fee);
        when(repo.lowAttendanceSections(10L, LocalDate.of(2026, 10, 12))).thenReturn(sections);
        when(repo.lowAttendanceStudents(10L, "s-1")).thenReturn(students);
        when(repo.vendorDues(10L)).thenReturn(vendor);
        when(repo.reorderSignals(10L)).thenReturn(reorder);

        Path contractPath = Path.of("contracts/openapi/reportingClient.v1.openapi.json").toAbsolutePath();
        if (!Files.exists(contractPath)) contractPath = Path.of("../../contracts/openapi/reportingClient.v1.openapi.json");
        JsonNode contract = json.readTree(Files.readString(contractPath));
        for (String path : List.of("/dashboard-command-center", "/fee-defaulters", "/low-attendance/sections",
                "/low-attendance/sections/s-1/students", "/vendor-dues", "/reorder-signals")) {
            var request = scoped(path);
            if (path.equals("/fee-defaulters")) request.param("classId", "c-1").param("sectionId", "s-1")
                    .param("daysOverdue", "30").param("reminderStatus", "NOT_SENT").param("page", "0").param("size", "25");
            if (path.equals("/low-attendance/sections")) request.param("date", "2026-10-12");
            String response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String template = path.replace("/s-1/students", "/{sectionId}/students");
            JsonNode schema = contract.path("paths").path("/api/v1/reporting" + template).path("get")
                    .path("responses").path("200").path("content").path("application/json").path("schema");
            assertThat(matches(json.readTree(response), schema, contract)).as("contract response: %s", path).isTrue();
        }
        verify(repo).dashboardCommandCenter(10L);
        verify(repo).feeDefaulters(10L, "c-1", "s-1", 30, "NOT_SENT", 0, 25);
        verify(repo).lowAttendanceSections(10L, LocalDate.of(2026, 10, 12));
        verify(repo).lowAttendanceStudents(10L, "s-1");
        verify(repo).vendorDues(10L);
        verify(repo).reorderSignals(10L);
    }

    // A bounded checker for the committed response subset, not a production JSON Schema validator.
    private boolean matches(JsonNode value, JsonNode schema, JsonNode root) {
        if (schema.has("$ref")) return matches(value, root.at(schema.path("$ref").asText().substring(1)), root);
        if (schema.has("anyOf")) {
            for (JsonNode option : schema.path("anyOf")) if (matches(value, option, root)) return true;
            return false;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode choice : schema.path("enum")) if (value.equals(choice)) found = true;
            if (!found) return false;
        }
        return switch (schema.path("type").asText()) {
            case "null" -> value.isNull();
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> {
                boolean valid = value.isArray();
                for (JsonNode item : value) valid &= matches(item, schema.path("items"), root);
                yield valid;
            }
            case "object" -> {
                boolean valid = value.isObject();
                for (JsonNode key : schema.path("required")) valid &= value.has(key.asText());
                for (String key : value.propertyNames()) {
                    JsonNode property = schema.path("properties").path(key);
                    valid &= !property.isMissingNode() && matches(value.path(key), property, root);
                }
                yield valid;
            }
            default -> false;
        };
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
