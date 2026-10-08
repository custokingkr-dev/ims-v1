package com.custoking.ims.schoolcoreservice.recovery;

import com.custoking.ims.schoolcoreservice.erasure.*;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;

/** Explicit one-shot owner invocation. No container, listener, scheduler or delivery-resume API. */
final class IsolatedStudentErasureRecovery implements AutoCloseable {
    private final RecoveryAuthorization authority;
    private final StudentReadRepository students;
    private final StudentErasureJournal journal;
    private final ErasureJournalStore store;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final javax.sql.DataSource source;
    private final String approvedUrl;
    private final ExecutorService controlReads=Executors.newFixedThreadPool(1, Thread.ofPlatform().daemon().name("recovery-freeze-",0).factory());
    private final Semaphore slots=new Semaphore(1);
    IsolatedStudentErasureRecovery(RecoveryAuthorization authority, StudentReadRepository students,
            StudentErasureJournal journal, ErasureJournalStore store, JdbcClient jdbc,
            TransactionTemplate transaction,javax.sql.DataSource source,String approvedUrl) {
        this.authority=Objects.requireNonNull(authority);this.students=Objects.requireNonNull(students);
        this.journal=Objects.requireNonNull(journal);this.store=Objects.requireNonNull(store);this.jdbc=Objects.requireNonNull(jdbc);
        this.transaction=Objects.requireNonNull(transaction);this.source=Objects.requireNonNull(source);this.approvedUrl=Objects.requireNonNull(approvedUrl);
        RecoveryAuthorization.require(transaction.getTimeout()>0 && transaction.getTimeout()<=45);
    }
    public Map<String,Object> execute() {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);int completed=0;
        // Failures intentionally produce no successful full-batch result. Committed source receipts
        // support independently verified restart; never assume a failed process rolled back prior tasks.
        for(JsonNode task:authority.plan.path("tasks")) {
            RecoveryAuthorization.require(System.nanoTime()<deadline);authority.requireFresh(45);freeze();
            long transactionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            transaction.execute(status->{
                jdbc.sql("SET LOCAL search_path=pg_catalog").update();
                jdbc.sql("SET LOCAL statement_timeout='10s'").update();jdbc.sql("SET LOCAL lock_timeout='3s'").update();
                StudentErasureRecoveryMain.requireTargetBinding(jdbc,source,approvedUrl);
                jdbc.sql("SELECT set_config('app.current_school_id', :school, true)").param("school",Long.toString(RecoveryAuthorization.positive(task,"schoolId"))).query(String.class).single();
                jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
                var reference=new StudentErasureJournal.IntentReference(RecoveryAuthorization.text(task,"object"),RecoveryAuthorization.generation(task,"generation"),RecoveryAuthorization.text(task,"sha256"));
                var receipt=journal.verifyForReplay(reference);
                RecoveryAuthorization.require(receipt.intentId().equals(RecoveryAuthorization.text(task,"intentId")) && receipt.operationId().toString().equals(RecoveryAuthorization.text(task,"operationId")) && receipt.studentId()==RecoveryAuthorization.positive(task,"studentId") && receipt.schoolId()==RecoveryAuthorization.positive(task,"schoolId") && receipt.incarnation().toString().equals(RecoveryAuthorization.text(task,"studentIncarnation")) && receipt.restoreEpoch().toString().equals(RecoveryAuthorization.text(task,"restoreEpoch")) && receipt.sourceLineageId().equals(RecoveryAuthorization.text(authority.approval,"lineage")));
                students.reconcileErasure(reference);
                authority.requireFresh(45);freeze();StudentErasureRecoveryMain.requireTargetBinding(jdbc,source,approvedUrl);RecoveryAuthorization.require(System.nanoTime()<deadline && System.nanoTime()<transactionDeadline);
                return null;
            });
            completed++;
        }
        return Map.of("mode","ISOLATED_SOURCE_REPLAY_ONLY","approvalSha256",authority.approvalSha,"completedSourceTasks",completed,"restorationReady",false,"deliveryResume",false,"downstreamReconciliationRequired",true,"physicalPhotoErasurePerformed",false,"freezeIsOperatorAttestation",true);
    }
    private void freeze() {
        RecoveryAuthorization.require(slots.tryAcquire());
        Future<ErasureJournalStore.StoredObject> future=controlReads.submit(()->{
            try{return store.readLatest(ErasureJournalConfiguration.BUCKET,RecoveryAuthorization.text(authority.approval,"freezeObject"),32768);}finally{slots.release();}
        });
        try {
            var observed=future.get(4,TimeUnit.SECONDS);
            RecoveryAuthorization.require(observed!=null && observed.generation()==RecoveryAuthorization.generation(authority.approval,"freezeGeneration") && RecoveryAuthorization.sha(observed.body()).equals(RecoveryAuthorization.text(authority.approval,"freezeSha256")));
            authority.verifyFreeze(observed.body());
        } catch(InterruptedException e){Thread.currentThread().interrupt();future.cancel(true);throw RecoveryAuthorization.rejected();}
        catch(Exception e){future.cancel(true);throw RecoveryAuthorization.rejected();}
    }
    @Override public void close(){controlReads.shutdownNow();}
}
