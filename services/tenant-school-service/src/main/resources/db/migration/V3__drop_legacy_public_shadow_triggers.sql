DROP TRIGGER IF EXISTS trg_tenant_school_shadow ON tenant_school.schools;
DROP TRIGGER IF EXISTS trg_tenant_school_section_shadow ON tenant_school.school_sections;
DROP TRIGGER IF EXISTS trg_tenant_staff_shadow ON tenant_school.staff_members;
DROP TRIGGER IF EXISTS trg_tenant_zone_shadow ON tenant_school.zones;
DROP TRIGGER IF EXISTS trg_tenant_zone_school_shadow ON tenant_school.zone_school_mappings;
DROP TRIGGER IF EXISTS trg_tenant_zone_school_shadow_delete ON tenant_school.zone_school_mappings;
DROP TRIGGER IF EXISTS trg_tenant_module_entitlement_shadow ON tenant_school.school_module_entitlements;

DROP FUNCTION IF EXISTS tenant_school.sync_school_shadow();
DROP FUNCTION IF EXISTS tenant_school.sync_school_section_shadow();
DROP FUNCTION IF EXISTS tenant_school.sync_staff_shadow();
DROP FUNCTION IF EXISTS tenant_school.sync_zone_shadow();
DROP FUNCTION IF EXISTS tenant_school.sync_zone_school_shadow();
DROP FUNCTION IF EXISTS tenant_school.delete_zone_school_shadow();
DROP FUNCTION IF EXISTS tenant_school.sync_module_entitlement_shadow();
