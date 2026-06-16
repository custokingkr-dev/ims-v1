package com.custoking.ims.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAccessTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void branchUserAlwaysResolvesToOwnSchool() {
        TenantContext.setScope(scope("ADMIN", 10L, List.of(), false));

        assertThat(TenantAccess.resolveSchoolId(null)).isEqualTo(10L);
        assertThat(TenantAccess.resolveSchoolId(10L)).isEqualTo(10L);
        assertThatThrownBy(() -> TenantAccess.resolveSchoolId(11L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void zoneAdminCanResolveMappedSchoolsOnly() {
        TenantContext.setScope(scope("ZONE_ADMIN", null, List.of(21L, 22L), false));

        assertThat(TenantAccess.resolveSchoolId(null)).isEqualTo(21L);
        assertThat(TenantAccess.resolveSchoolId(22L)).isEqualTo(22L);
        assertThatThrownBy(() -> TenantAccess.resolveSchoolId(23L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void superadminCanStayGlobalOrChooseAnySchool() {
        TenantContext.setScope(scope("SUPERADMIN", null, List.of(), true));

        assertThat(TenantAccess.resolveSchoolId(null)).isNull();
        assertThat(TenantAccess.resolveSchoolId(99L)).isEqualTo(99L);
    }

    private TenantScope scope(String role, Long schoolId, List<Long> accessibleSchools, boolean superadmin) {
        return new TenantScope(
                1L,
                role,
                List.of(role),
                Set.of(),
                schoolId,
                schoolId == null ? null : "School " + schoolId,
                null,
                null,
                accessibleSchools,
                superadmin);
    }
}
