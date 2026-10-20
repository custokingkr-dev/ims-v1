package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowSecurityIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static HikariDataSource pool;
    static JdbcClient jdbc;
    static WorkflowReadRepository repo;
    static TransactionTemplate tx;
    @BeforeAll static void setup() {
        pg = new PostgreSQLContainer<>("postgres:16"); pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .schemas("workflow").defaultSchema("workflow").locations("classpath:db/migration/workflow").load().migrate();
        pool = new HikariDataSource(); pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername(pg.getUsername()); pool.setPassword(pg.getPassword()); pool.setMaximumPoolSize(4);
        jdbc = JdbcClient.create(pool); repo = new WorkflowReadRepository(jdbc, "workflow"); tx = new TransactionTemplate(new JdbcTransactionManager(pool));
        jdbc.sql("INSERT INTO workflow.workflow_definitions(id,name) VALUES ('SECURITY_TEST','Security test')").update();
        jdbc.sql("INSERT INTO workflow.workflow_steps(definition_id,step_order,step_name,required_permission,required_role) VALUES ('SECURITY_TEST',1,'Approval','order:approve','PRINCIPAL')").update();
    }
    @AfterAll static void stop() { if (pool != null) pool.close(); if (pg != null) pg.stop(); }
    @AfterEach void clear() { TenantContext.clear(); }
    private void actor(long id, String role, long school, String... permissions) { TenantContext.set(new TenantContext(id,"actor"+id+"@x",role,school,null,Set.of(permissions))); }
    private long instance(String status) {
        return jdbc.sql("INSERT INTO workflow.workflow_instances(definition_id,entity_type,entity_id,school_id,current_step,status,initiated_by) VALUES ('SECURITY_TEST','ORDER',:entity,10,1,:status,1) RETURNING id")
                .param("entity",UUID.randomUUID().toString()).param("status",status).query(Long.class).single();
    }
    private Map<String,Object> approve(long id) { return tx.execute(s -> repo.approve(id,Map.of("expectedVersion",0L,"actorId",999L,"actorEmail","forged@x"))); }
    private long actions(long id) { return jdbc.sql("SELECT count(*) FROM workflow.workflow_actions WHERE instance_id=:id").param("id",id).query(Long.class).single(); }
    @Test void stepPermissionDeniedHasNoSideEffects() {
        long id=instance("IN_PROGRESS"); actor(2,"PRINCIPAL",10,"workflow:act");
        assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class);
        assertThat(actions(id)).isZero(); assertThat(repo.instance(id).orElseThrow().version()).isZero();
    }
    @Test void stepRoleDeniedEvenWithPermission() {
        long id=instance("IN_PROGRESS"); actor(2,"SCHOOL_ADMIN",10,"workflow:act","order:approve");
        assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class); assertThat(actions(id)).isZero();
    }
    @Test void selfApprovalDeniedIncludingSuperadmin() {
        long id=instance("IN_PROGRESS"); actor(1,"SUPERADMIN",10);
        assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class); assertThat(actions(id)).isZero();
    }
    @Test void authorizedDecisionUsesAuthenticatedAttribution() {
        long id=instance("IN_PROGRESS"); actor(2,"PRINCIPAL",10,"workflow:act","order:approve");
        assertThat(approve(id)).containsEntry("status","APPROVED").containsEntry("version",1L);
        assertThat(repo.actions(id).getFirst().actorId()).isEqualTo(2); assertThat(repo.actions(id).getFirst().actorEmail()).isEqualTo("actor2@x");
    }
    @Test void crossSchoolDeniedEvenWithDatabaseOwnerConnection() {
        long id=instance("IN_PROGRESS"); actor(2,"PRINCIPAL",20,"workflow:act","order:approve");
        assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class); assertThat(actions(id)).isZero();
    }
    @Test void anonymousDenied() {
        long id=instance("IN_PROGRESS"); TenantContext.clear(); assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class); assertThat(actions(id)).isZero();
    }
    @Test void illegalCompleteAndTerminalCancellationDoNotMutate() {
        actor(2,"SUPERADMIN",10);
        for(String status: List.of("PENDING","IN_PROGRESS","REJECTED","CANCELLED","COMPLETED")) {
            long id=instance(status); assertThatThrownBy(() -> tx.execute(s -> repo.complete(id,Map.of()))).isInstanceOf(IllegalArgumentException.class); assertThat(actions(id)).isZero();
        }
        for(String status: List.of("APPROVED","REJECTED","CANCELLED","COMPLETED")) {
            long id=instance(status); assertThatThrownBy(() -> tx.execute(s -> repo.cancel(id,Map.of()))).isInstanceOf(IllegalArgumentException.class); assertThat(actions(id)).isZero();
        }
    }
    @Test void duplicateConcurrentDecisionAdvancesExactlyOnce() throws Exception {
        long id=instance("IN_PROGRESS"); CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> decide=() -> { actor(2,"PRINCIPAL",10,"workflow:act","order:approve"); start.await(); try { approve(id); return true; } catch(ResponseStatusException ex) { assertThat(ex.getStatusCode().value()).isEqualTo(409); return false; } finally { TenantContext.clear(); } };
            var one=executor.submit(decide); var two=executor.submit(decide); start.countDown(); assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(actions(id)).isEqualTo(1); assertThat(repo.instance(id).orElseThrow().version()).isEqualTo(1);
    }
    @Test void injectedNotesAreDataAndActionFailureRollsBackState() {
        long id=instance("IN_PROGRESS"); actor(2,"PRINCIPAL",10,"workflow:act","order:approve");
        String text="'); DROP TABLE workflow.workflow_instances; --";
        tx.execute(s -> repo.approve(id,Map.of("expectedVersion",0L,"notes",text)));
        assertThat(repo.actions(id).getFirst().notes()).isEqualTo(text);
        long broken=instance("IN_PROGRESS");
        assertThatThrownBy(() -> tx.execute(s -> repo.approve(broken,Map.of("expectedVersion",0L,"notes","x".repeat(1001))))).isInstanceOf(RuntimeException.class);
        assertThat(actions(broken)).isZero(); assertThat(repo.instance(broken).orElseThrow().status()).isEqualTo("IN_PROGRESS");
    }
    @Test void rolePermissionMatrixPreservesDenialsAndPrivilegedDelegation() {
        for(String role:List.of("SUPERADMIN","PRINCIPAL","ADMIN","SCHOOL_ADMIN","OPERATIONS","TEACHER")) {
            for(boolean permission:List.of(false,true)) {
                long id=instance("IN_PROGRESS"); actor(2,role,10, permission ? new String[]{"workflow:act","order:approve"} : new String[]{"workflow:act"});
                if(role.equals("SUPERADMIN") || role.equals("PRINCIPAL") && permission) { approve(id);assertThat(actions(id)).isEqualTo(1); }
                else { assertThatThrownBy(() -> approve(id)).isInstanceOf(ResponseStatusException.class);assertThat(actions(id)).isZero();assertThat(repo.instance(id).orElseThrow().version()).isZero(); }
            }
        }
    }
    @Test void completionRequiresFulfillmentAndOnlyApprovedState() {
        long id=instance("APPROVED");actor(2,"PRINCIPAL",10,"workflow:act","order:approve");
        assertThatThrownBy(() -> tx.execute(s -> repo.complete(id,Map.of()))).isInstanceOf(ResponseStatusException.class); assertThat(actions(id)).isZero();
        actor(2,"OPERATIONS",10,"workflow:act","order:fulfill");
        assertThat(tx.<Map<String,Object>>execute(s -> repo.complete(id,Map.of()))).containsEntry("status","COMPLETED").containsEntry("version",1L);assertThat(actions(id)).isEqualTo(1);
    }
    @Test void submissionAndCancellationBelongToInitiatorOrDelegateSuperadmin() {
        long id=instance("PENDING");actor(2,"PRINCIPAL",10,"workflow:act");
        assertThatThrownBy(() -> tx.execute(s -> repo.submit(id,Map.of()))).isInstanceOf(ResponseStatusException.class);assertThat(actions(id)).isZero();
        actor(1,"ADMIN",10,"workflow:act");
        assertThat(tx.<Map<String,Object>>execute(s -> repo.submit(id,Map.of()))).containsEntry("status","IN_PROGRESS").containsEntry("version",1L);
        assertThat(tx.<Map<String,Object>>execute(s -> repo.cancel(id,Map.of()))).containsEntry("status","CANCELLED").containsEntry("version",2L);assertThat(actions(id)).isEqualTo(2);
    }
    @Test void concurrentCreationIsScopedAndUnique() throws Exception {
        String entity=UUID.randomUUID().toString();CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Long> create=() -> {actor(1,"ADMIN",10,"workflow:act","order:create");start.await();try{return (Long)tx.execute(s -> repo.createOrGetInstance(Map.of("entityType","ORDER","entityId",entity,"definitionId","SECURITY_TEST","schoolId",10L))).get("id");}finally{TenantContext.clear();}};
            var one=executor.submit(create);var two=executor.submit(create);start.countDown();assertThat(one.get(10,TimeUnit.SECONDS)).isEqualTo(two.get(10,TimeUnit.SECONDS));
        }
        actor(1,"ADMIN",20,"workflow:act","order:create");
        assertThat(tx.<Map<String,Object>>execute(s -> repo.createOrGetInstance(Map.of("entityType","ORDER","entityId",entity,"definitionId","SECURITY_TEST","schoolId",20L)))).containsEntry("schoolId",20L);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow.workflow_instances WHERE entity_id=:entity").param("entity",entity).query(Long.class).single()).isEqualTo(2);
    }
}
