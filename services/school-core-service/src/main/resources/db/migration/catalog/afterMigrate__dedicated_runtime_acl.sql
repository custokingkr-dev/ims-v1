-- Resynchronize dedicated runtime ACLs after every owner migration, before traffic admission.
-- Do not provision credentials here or grant any cross-service schema capability.
DO $runtime_acl$ DECLARE
  role_name text := 'ims_school_core_rt'; schema_name text := 'catalog';
  relation record; attribute record; operation text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name) THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication OR rolinherit))
     OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=role_name)
  THEN RAISE EXCEPTION 'Unsafe dedicated runtime role %',role_name; END IF;
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I',schema_name,role_name);
  FOR relation IN SELECT c.oid,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=schema_name AND c.relkind IN ('r','p','v','m') AND c.relname<>'flyway_schema_history'
  LOOP
    EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM %I',schema_name,relation.relname,role_name);
    FOREACH operation IN ARRAY ARRAY['SELECT','INSERT','UPDATE','DELETE'] LOOP
      IF has_table_privilege('app_rt',relation.oid,operation) THEN
        EXECUTE format('GRANT %s ON TABLE %I.%I TO %I',operation,schema_name,relation.relname,role_name);
      ELSIF operation IN ('SELECT','INSERT','UPDATE') THEN
        FOR attribute IN SELECT attname FROM pg_attribute WHERE attrelid=relation.oid AND attnum>0 AND NOT attisdropped
            AND has_column_privilege('app_rt',relation.oid,attnum,operation)
        LOOP EXECUTE format('GRANT %s (%I) ON TABLE %I.%I TO %I',operation,attribute.attname,schema_name,relation.relname,role_name); END LOOP;
      END IF;
    END LOOP;
  END LOOP;
  FOR relation IN SELECT c.oid,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=schema_name AND c.relkind='S'
  LOOP
    EXECUTE format('REVOKE ALL ON SEQUENCE %I.%I FROM %I',schema_name,relation.relname,role_name);
    FOREACH operation IN ARRAY ARRAY['USAGE','SELECT','UPDATE'] LOOP
      IF has_sequence_privilege('app_rt',relation.oid,operation) THEN
        EXECUTE format('GRANT %s ON SEQUENCE %I.%I TO %I',operation,schema_name,relation.relname,role_name);
      END IF;
    END LOOP;
  END LOOP;
  FOR relation IN SELECT p.oid,p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=schema_name
  LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM %I',relation.signature,role_name);
    IF has_function_privilege('app_rt',relation.oid,'EXECUTE') THEN
      EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I',relation.signature,role_name);
    END IF;
  END LOOP;
END $runtime_acl$;
