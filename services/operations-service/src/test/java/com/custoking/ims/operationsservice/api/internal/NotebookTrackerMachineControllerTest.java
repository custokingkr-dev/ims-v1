package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.application.NotebookTrackerService;
import com.custoking.ims.operationsservice.application.NotebookTrackerService.CreateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** School-core's two tracker calls are served on the internal path with the bodies they had before; caller checks live in MachineCallerFilter. */
class NotebookTrackerMachineControllerTest {
    private static final String CREATE = "{\"orderId\":\"CK-1\",\"schoolId\":4,\"customised\":true,\"orderedCount\":2000,"
            + "\"placedAt\":\"2026-10-19T09:30:00+05:30\",\"timeZone\":\"Asia/Kolkata\",\"placedBy\":7,\"assigneeUserId\":42,\"schoolName\":\"School A\"}";

    NotebookTrackerService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(NotebookTrackerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotebookTrackerMachineController(service)).build();
    }

    @Test void createPassesTheOrderFactsOnAndAnswersCreatedOrExisting() throws Exception {
        when(service.create(any())).thenReturn(new NotebookTrackerService.Created(9L, true), new NotebookTrackerService.Created(9L, false));
        mvc.perform(post("/api/v1/internal/notebook-trackers").contentType("application/json").content(CREATE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.instanceId").value(9)).andExpect(jsonPath("$.created").value(true));
        mvc.perform(post("/api/v1/internal/notebook-trackers").contentType("application/json").content(CREATE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instanceId").value(9)).andExpect(jsonPath("$.created").value(false));

        ArgumentCaptor<CreateTracker> sent = ArgumentCaptor.forClass(CreateTracker.class);
        verify(service, times(2)).create(sent.capture());
        CreateTracker command = sent.getValue();
        assertEquals("CK-1", command.orderId());
        assertEquals(4L, command.schoolId());
        assertTrue(command.customised());
        assertEquals(2000, command.orderedCount());
        assertTrue(command.placedAt().isEqual(OffsetDateTime.parse("2026-10-19T09:30:00+05:30")), String.valueOf(command.placedAt()));
        assertEquals("Asia/Kolkata", command.timeZone());
        assertEquals(7L, command.placedBy());
        assertEquals(42L, command.assigneeUserId());
        assertEquals("School A", command.schoolName());
    }

    @Test void createWithoutTheRequiredFactsIsA400() throws Exception {
        mvc.perform(post("/api/v1/internal/notebook-trackers").contentType("application/json").content("{\"orderId\":\"CK-1\",\"customised\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void imageAccessPassesTheUserAndRoleOnAndReturnsTheAnswer() throws Exception {
        when(service.designImageAccess("CK-1", 8L, "OPERATIONS")).thenReturn(new NotebookTrackerService.ImageAccessResult(false, "locked"));
        mvc.perform(post("/api/v1/internal/notebook-trackers/CK-1/image-access").contentType("application/json")
                        .content("{\"userId\":8,\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reason").value("locked"));
        verify(service).designImageAccess("CK-1", 8L, "OPERATIONS");
    }

    @Test void imageAccessWithoutAUserIsA400() throws Exception {
        mvc.perform(post("/api/v1/internal/notebook-trackers/CK-1/image-access").contentType("application/json").content("{\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).designImageAccess(any(), anyLong(), any());
    }
}
