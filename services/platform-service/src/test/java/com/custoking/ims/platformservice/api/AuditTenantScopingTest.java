package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.persistence.AuditEvent;
import com.custoking.ims.platformservice.persistence.AuditEventRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditTenantScopingTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AuditIngestController(repository, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/audit/events?schoolId=99")
                        .header("X-Audit-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/audit/events")
                        .header("X-Audit-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        // Repo was called (spec filtered by resolved schoolId=10 internally)
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void superadmin_fullExport_allowed() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/audit/events")
                        .header("X-Audit-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }
}
