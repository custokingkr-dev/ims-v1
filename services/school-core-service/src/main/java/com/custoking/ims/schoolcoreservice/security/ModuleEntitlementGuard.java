package com.custoking.ims.schoolcoreservice.security;

import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class ModuleEntitlementGuard {

    private static final List<String> ERP_MODULES = List.of(
            "STUDENTS", "ATTENDANCE", "FEES", "INVOICES", "PAYMENTS", "REPORTS");

    private final ModuleEntitlementReadRepository modules;

    public ModuleEntitlementGuard(ModuleEntitlementReadRepository modules) {
        this.modules = modules;
    }

    public void requireErpEnabled(Long schoolId) {
        if (TenantContext.get().isSuperAdmin()) {
            return;
        }
        if (schoolId == null || !modules.anyEnabled(schoolId, ERP_MODULES)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ERP module is not enabled for this school");
        }
    }
}
