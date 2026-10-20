-- Forward preparation only, executed by the migration owner. Does not revoke app_rt or
-- switch applications. Provision managed passwords separately without logging them.
-- Scope comes from RUNTIME_SCHEMA_DEPENDENCY_BASELINE after removing platform/student join.
BEGIN;
DO $roles$
DECLARE
    service_role text;
    owned_schema text;
    table_row record;
    column_row record;
    operation text;
    mapping jsonb := '{"ims_identity_rt":["identity"],"ims_school_core_rt":["tenant_school","student","attendance","fee","catalog"],"ims_operations_rt":["workflow","firefighting"],"ims_platform_rt":["reporting","notification","audit"],"ims_billing_rt":["billing"]}';
BEGIN
    FOR service_role IN SELECT jsonb_object_keys(mapping) LOOP
        IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=service_role) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',service_role);
        END IF;
        IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname=service_role AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolinherit))
            OR EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=service_role)
            OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner WHERE r.rolname=service_role)
        THEN RAISE EXCEPTION 'Unsafe existing service role %',service_role; END IF;
        FOR owned_schema IN SELECT jsonb_array_elements_text(mapping->service_role) LOOP
            EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I',owned_schema,service_role);
            FOR table_row IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=owned_schema AND c.relkind IN ('r','p','v','m') AND c.relname<>'flyway_schema_history'
            LOOP
                -- Preserve reviewed append-only/column-specific app_rt restrictions rather
                -- than silently widening queue identities or immutable document manifests.
                EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM %I',owned_schema,table_row.relname,service_role);
                FOREACH operation IN ARRAY ARRAY['SELECT','INSERT','UPDATE','DELETE'] LOOP
                    IF has_table_privilege('app_rt',format('%I.%I',owned_schema,table_row.relname),operation) THEN
                        EXECUTE format('GRANT %s ON TABLE %I.%I TO %I',operation,owned_schema,table_row.relname,service_role);
                    ELSIF operation IN ('SELECT','INSERT','UPDATE') THEN
                        FOR column_row IN SELECT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                            JOIN pg_namespace n ON n.oid=c.relnamespace
                            WHERE n.nspname=owned_schema AND c.relname=table_row.relname AND a.attnum>0 AND NOT a.attisdropped
                              AND has_column_privilege('app_rt',c.oid,a.attnum,operation)
                        LOOP
                            EXECUTE format('GRANT %s (%I) ON TABLE %I.%I TO %I',operation,column_row.attname,owned_schema,table_row.relname,service_role);
                        END LOOP;
                    END IF;
                END LOOP;
            END LOOP;
            EXECUTE format('GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I',owned_schema,service_role);
            FOR table_row IN SELECT p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname=owned_schema AND has_function_privilege('app_rt',p.oid,'EXECUTE')
            LOOP
                EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I',table_row.signature,service_role);
            END LOOP;
            -- No broad default table grants: migrations must name the service role or this
            -- reviewed synchronization must run after Flyway and before traffic admission.
        END LOOP;
    END LOOP;
END $roles$;
COMMIT;
-- Cutover one dev service at a time: SPRING_DATASOURCE_USERNAME=ims_<service>_rt,
-- RUNTIME_DB_ROLE=the_same_role, separate managed password ref; Flyway remains owner.
-- Inspect PUBLIC table/function grants, effective pg_has_role reachability, startup guard,
-- cross-schema denial and real tenant/pool tests before moving another service.
-- Keep app_rt until all revisions/jobs/drains use the dedicated roles; then revoke/remove
-- shared credentials in a SEPARATELY reviewed operation. Rollback before shared-role
-- removal restores the previous username and managed secret reference, never owner credentials.
