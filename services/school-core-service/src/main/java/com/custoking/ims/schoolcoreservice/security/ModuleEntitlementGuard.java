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
        TenantContext ctx = TenantContext.get();
        if (!ctx.isAuthenticated() || ctx.isSuperAdmin()) {
            return;
        }
        if (schoolId == null || !modules.anyEnabled(schoolId, ERP_MODULES)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ERP module is not enabled for this school");
        }
    }

    public void requireModuleEnabled(Long schoolId, String moduleCode) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isAuthenticated() || ctx.isSuperAdmin()) {
            return;
        }
        if (ctx.isOperations() && schoolId == null) {
            if (moduleCode != null && !moduleCode.isBlank() && !ctx.operatorSchools().isEmpty()) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, moduleCode + " module is not enabled for any assigned school");
        }
        if (schoolId == null || moduleCode == null || moduleCode.isBlank()
                || !modules.anyEnabled(schoolId, List.of(moduleCode))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, moduleCode + " module is not enabled for this school");
        }
    }
}
