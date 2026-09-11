package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract of {@code GET /attendance/absentees} and {@code POST /attendance/absentees/notify}:
 * who may call them, where the school scope and the actor come from, and how the optional
 * class/section filters reach the repository. The repository is mocked; the real
 * {@link TenantContextFilter} populates {@link TenantContext} from the gateway headers.
 */
class AttendanceAbsenteeControllerTest {

    private static final String TOKEN = "attendance-token";

    private final AttendanceReadRepository attendance = mock(AttendanceReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AttendanceReadController(attendance, TOKEN))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── POST /absentees/notify ──────────────────────────────────────────────────

    @Test
    void notify_stampsTheActorFromTheAuthenticatedUser_andIgnoresAnyActorInTheBody() throws Exception {
        when(attendance.notifyAbsentees(LocalDate.parse("2026-04-06"), "c1", null, 10L, 42L))
                .thenReturn(Map.of("queued", 1));

        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        // A client trying to attribute the action to someone else.
                        .content("{\"date\":\"2026-04-06\",\"classId\":\"c1\",\"actorId\":999,\"queuedBy\":999,\"userId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queued").value(1));

        verify(attendance).notifyAbsentees(LocalDate.parse("2026-04-06"), "c1", null, 10L, 42L);
        verify(attendance, never()).notifyAbsentees(any(), any(), any(), any(), eq(999L));
    }

    @Test
    void notify_classIdAlone_reachesTheRepositoryWithANullSection() throws Exception {
        when(attendance.notifyAbsentees(any(), eq("c1"), isNull(), eq(10L), eq(42L))).thenReturn(Map.of("queued", 0));

        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-04-06\",\"classId\":\"c1\"}"))
                .andExpect(status().isOk());

        verify(attendance).notifyAbsentees(LocalDate.parse("2026-04-06"), "c1", null, 10L, 42L);
    }

    @Test
    void notify_requiresAttendanceManage_notJustRead() throws Exception {
        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "TEACHER")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-04-06\"}"))
                .andExpect(status().isForbidden());

        verify(attendance, never()).notifyAbsentees(any(), any(), any(), any(), any());
    }

    @Test
    void notify_crossTenantSchoolIdInTheBody_isForbidden() throws Exception {
        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-04-06\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());

        verify(attendance, never()).notifyAbsentees(any(), any(), any(), any(), any());
    }

    @Test
    void notify_superadmin_mayTargetAnySchool_andStillCarriesItsOwnUserId() throws Exception {
        when(attendance.notifyAbsentees(LocalDate.parse("2026-04-06"), null, "s9", 99L, 1L))
                .thenReturn(Map.of("queued", 3));

        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "1")
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-04-06\",\"sectionId\":\"s9\",\"schoolId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queued").value(3));
    }

    @Test
    void notify_blankDate_defaultsToToday() throws Exception {
        when(attendance.notifyAbsentees(eq(LocalDate.now()), isNull(), isNull(), eq(10L), eq(42L)))
                .thenReturn(Map.of("queued", 0));

        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"  \"}"))
                .andExpect(status().isOk());

        verify(attendance).notifyAbsentees(LocalDate.now(), null, null, 10L, 42L);
    }

    @Test
    void notify_unparseableDate_isABadRequest_notAServerError() throws Exception {
        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"06/04/2026\"}"))
                .andExpect(status().isBadRequest());

        verify(attendance, never()).notifyAbsentees(any(), any(), any(), any(), any());
    }

    @Test
    void notify_withoutTheServiceToken_isUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/attendance/absentees/notify")
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-04-06\"}"))
                .andExpect(status().isUnauthorized());

        verify(attendance, never()).notifyAbsentees(any(), any(), any(), any(), any());
    }

    // ── GET /absentees ──────────────────────────────────────────────────────────

    @Test
    void absentees_classIdAlone_isPassedThroughWithANullSection() throws Exception {
        when(attendance.absentees(LocalDate.parse("2026-04-06"), "c1", null, 10L))
                .thenReturn(Map.of("totalAbsent", 2));

        mvc.perform(get("/api/v1/attendance/absentees")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-User-Id", "42")
                        .header("X-Authenticated-Role", "TEACHER")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:read")
                        .param("date", "2026-04-06")
                        .param("classId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAbsent").value(2));

        verify(attendance).absentees(LocalDate.parse("2026-04-06"), "c1", null, 10L);
    }

    @Test
    void absentees_crossTenantSchoolIdParam_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/attendance/absentees")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "attendance:read")
                        .param("date", "2026-04-06")
                        .param("schoolId", "99"))
                .andExpect(status().isForbidden());

        verify(attendance, never()).absentees(any(), any(), any(), any());
    }

    @Test
    void absentees_requiresAttendanceRead() throws Exception {
        mvc.perform(get("/api/v1/attendance/absentees")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "STAFF")
                        .header("X-Authenticated-School-Id", "10")
                        .header("X-Authenticated-Permissions", "fee:read")
                        .param("date", "2026-04-06"))
                .andExpect(status().isForbidden());

        verify(attendance, never()).absentees(any(), any(), any(), any());
    }

    @Test
    void absentees_dateIsMandatory() throws Exception {
        mvc.perform(get("/api/v1/attendance/absentees")
                        .header("X-Attendance-Service-Token", TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isBadRequest());
    }
}
