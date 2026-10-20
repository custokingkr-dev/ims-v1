package com.custoking.ims.schoolcoreservice.recovery;

import com.custoking.ims.schoolcoreservice.erasure.*;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.util.*;

/** Alternate explicit main only. No SpringApplication, context, Flyway, web, workers or controllers. */
public final class StudentErasureRecoveryMain {
    private static final Path POLICY=Path.of("/etc/custoking/recovery/trust-policy.json");
    private static final Path INPUT=Path.of("/run/custoking-recovery");
    public static void main(String[] args) {
        try {
            requireBootstrap(args,System.getenv());
            byte[] policy=protectedRead(POLICY,16384), approval=protectedRead(INPUT.resolve("approval.json"),32768), work=protectedRead(INPUT.resolve("worklist.json"),2*1024*1024), physical=protectedRead(INPUT.resolve("target-proof.json"),32768);
            RecoveryAuthorization a=new RecoveryAuthorization(policy,approval,work,physical,Clock.systemUTC());
            byte[] ca=protectedRead(INPUT.resolve("clone-ca.pem"),65536);
            requireCa(ca,RecoveryAuthorization.parse(physical,32768));
            String password=decodePassword(protectedCredentialRead(INPUT.resolve("database-password")));
            try(HikariDataSource data=new HikariDataSource()) {
                // Exact approved numeric private address: no caller DNS/URL/query/credential override.
                String ip=RecoveryAuthorization.text(a.approval,"privateIp");
                String approvedUrl="jdbc:postgresql://"+ip+":5432/custoking_dev";
                data.setJdbcUrl(approvedUrl);data.setUsername(RecoveryAuthorization.PURPOSE_ROLE);data.setPassword(password);
                data.setMaximumPoolSize(1);data.setMinimumIdle(0);data.setConnectionTimeout(3000);data.setValidationTimeout(2000);data.setInitializationFailTimeout(-1);
                data.addDataSourceProperty("sslmode","verify-ca");data.addDataSourceProperty("sslrootcert",INPUT.resolve("clone-ca.pem").toString());data.addDataSourceProperty("connectTimeout","5");data.addDataSourceProperty("socketTimeout","10");data.addDataSourceProperty("cancelSignalTimeout","2");data.addDataSourceProperty("ApplicationName","ims-dev-isolated-erasure-recovery");
                JdbcClient jdbc=JdbcClient.create(data);var tx=new TransactionTemplate(new JdbcTransactionManager(data));tx.setTimeout(45);
                var configuration=new ErasureJournalConfiguration(true,"custoking-dev",ErasureJournalConfiguration.BUCKET,RecoveryAuthorization.text(a.approval,"lineage"),RecoveryAuthorization.text(a.approval,"epoch"),RecoveryAuthorization.generation(a.approval,"epochGeneration"),RecoveryAuthorization.text(a.approval,"epochSha256"));
                var store=new GcsErasureJournalStore();var journal=new StudentErasureJournal(configuration,store);
                var photos=new StudentPhotoStorage(RecoveryAuthorization.text(a.approval,"photoBucket"),5,512,5242880,"");
                var repository=new StudentReadRepository(jdbc,photos,new OutboxWriter(jdbc,new ObjectMapper(),"tenant_school"));repository.setErasureJournal(journal);
                try(var executor=new IsolatedStudentErasureRecovery(a,repository,journal,store,jdbc,tx,data,approvedUrl)) {
                    Map<String,Object> result=executor.execute();System.out.println(new ObjectMapper().writeValueAsString(result));
                }
            }
        }catch(Exception rejected){System.err.println("ISOLATED_RECOVERY_REJECTED; preserve quarantine and inspect restricted owner receipts before retry.");System.exit(1);}
    }
    static String decodePassword(byte[] raw) {
        try {
            String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            // Mounted secret contract is exact single-line UTF-8, without newline/NUL terminator.
            // Spaces are preserved; credentials are never silently trimmed or normalized.
            RecoveryAuthorization.require(!value.isEmpty()&&value.length()<=512&&value.indexOf('\n')<0&&value.indexOf('\r')<0&&value.indexOf('\0')<0);return value;
        }catch(java.nio.charset.CharacterCodingException invalid){throw RecoveryAuthorization.rejected();}
    }
    static void requireCredentialPermissions(Set<PosixFilePermission> file,Set<PosixFilePermission> parent) {
        RecoveryAuthorization.require(!file.contains(PosixFilePermission.OTHERS_READ)&&!parent.contains(PosixFilePermission.OTHERS_EXECUTE));
    }
    static byte[] protectedCredentialRead(Path path)throws Exception {
        requireCredentialPermissions(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS),Files.getPosixFilePermissions(path.getParent(),LinkOption.NOFOLLOW_LINKS));
        byte[] value=protectedRead(path,1024);
        requireCredentialPermissions(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS),Files.getPosixFilePermissions(path.getParent(),LinkOption.NOFOLLOW_LINKS));return value;
    }
    static void requireCa(byte[] pem,tools.jackson.databind.JsonNode proof) {
        RecoveryAuthorization.require(RecoveryAuthorization.sha(pem).equals(RecoveryAuthorization.text(proof,"cloneCaPemSha256")));
        String text=new String(pem,java.nio.charset.StandardCharsets.US_ASCII);
        RecoveryAuthorization.require(text.split("-----BEGIN CERTIFICATE-----",-1).length==2 && text.split("-----END CERTIFICATE-----",-1).length==2);
        try {
            var certificates=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem));RecoveryAuthorization.require(certificates.size()==1);
            byte[] der=certificates.iterator().next().getEncoded();
            RecoveryAuthorization.require(RecoveryAuthorization.sha(der).equals(RecoveryAuthorization.text(proof.path("verification"),"cloneCaSha256")));
        }catch(java.security.cert.CertificateException invalid){throw RecoveryAuthorization.rejected();}
    }
    static void requireBootstrap(String[] args,Map<String,String> environment) {
        RecoveryAuthorization.require(args.length==0 && "REVIEWED_DEV_ISOLATED".equals(environment.get("APP_RECOVERY_BOOTSTRAP")) && !environment.containsKey("K_SERVICE"));
    }
    static void requireTargetBinding(JdbcClient jdbc,javax.sql.DataSource source,String approvedUrl) {
        java.sql.Connection connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
        try {RecoveryAuthorization.require(approvedUrl.equals(connection.getMetaData().getURL()));}
        catch(java.sql.SQLException unavailable){throw RecoveryAuthorization.rejected();}
        finally {org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,source);}
        boolean safe=jdbc.sql("""
            SELECT current_database()='custoking_dev' AND current_user='ims_dev_restore_executor'
              AND session_user=current_user
              AND EXISTS(SELECT 1 FROM pg_catalog.pg_stat_ssl WHERE pid=pg_backend_pid() AND ssl)
              AND EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=current_user
                  AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb
                  AND NOT rolreplication AND NOT rolinherit)
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_database WHERE datdba=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_class WHERE relowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_proc WHERE proowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user))
              AND NOT has_database_privilege(current_user,current_database(),'CREATE')
              AND NOT has_database_privilege(current_user,current_database(),'TEMP')
              AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname !~ '^pg_temp_' AND has_schema_privilege(current_user,oid,'CREATE'))
              -- Terminal receipts must remain append-only even for this isolated executor.
              -- has_any_column_privilege includes table grants and individual column grants.
              AND NOT has_any_column_privilege(current_user,'student.erasure_journal_receipts','UPDATE')
              AND NOT has_table_privilege(current_user,'student.erasure_journal_receipts','DELETE')
              AND NOT has_table_privilege(current_user,'student.erasure_journal_receipts','TRUNCATE')
              -- Replay may enqueue cleanup; it may never erase the durable cleanup evidence.
              -- UPDATE remains available to separately governed cleanup processing.
              AND NOT has_table_privilege(current_user,'tenant_school.photo_cleanup_outbox','DELETE')
              AND NOT has_table_privilege(current_user,'tenant_school.photo_cleanup_outbox','TRUNCATE')
            """).query(Boolean.class).single();
        RecoveryAuthorization.require(safe);
    }
    static byte[] protectedRead(Path path,int limit) throws Exception {
        Path absolute=path.toAbsolutePath().normalize();
        // Unsupported ownership environments (including Windows bootstrap) intentionally refuse.
        for(Path current=absolute;current!=null;current=current.getParent()) {
            RecoveryAuthorization.require(!Files.isSymbolicLink(current) && ((Number)Files.getAttribute(current,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()==0);
            Set<PosixFilePermission> mode=Files.getPosixFilePermissions(current,LinkOption.NOFOLLOW_LINKS);
            RecoveryAuthorization.require(!mode.contains(PosixFilePermission.GROUP_WRITE)&&!mode.contains(PosixFilePermission.OTHERS_WRITE));
            if(current.equals(absolute))RecoveryAuthorization.require(!mode.contains(PosixFilePermission.OWNER_WRITE));
        }
        BasicFileAttributes before=Files.readAttributes(absolute,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        RecoveryAuthorization.require(before.isRegularFile()&&before.size()>0&&before.size()<=limit);
        try(FileChannel channel=FileChannel.open(absolute,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes=ByteBuffer.allocate(limit+1);while(bytes.hasRemaining()&&channel.read(bytes)>=0){}
            RecoveryAuthorization.require(bytes.position()<=limit);
            BasicFileAttributes after=Files.readAttributes(absolute,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            RecoveryAuthorization.require(before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&Objects.equals(before.fileKey(),after.fileKey()));
            return Arrays.copyOf(bytes.array(),bytes.position());
        }
    }
    private StudentErasureRecoveryMain(){}
}
