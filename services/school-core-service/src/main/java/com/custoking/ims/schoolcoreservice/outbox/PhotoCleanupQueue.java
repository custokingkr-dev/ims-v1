package com.custoking.ims.schoolcoreservice.outbox;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.CleanupTarget;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class PhotoCleanupQueue {
    private final JdbcClient jdbc;
    public PhotoCleanupQueue(JdbcClient jdbc) { this.jdbc=jdbc; }
    public void enqueue(UUID operation, long schoolId, long studentId, CleanupTarget target) {
        jdbc.sql("""
            INSERT INTO tenant_school.photo_cleanup_outbox(id,erasure_operation_id,school_id,student_id,bucket,object_key)
            VALUES(:id,:operation,:school,:student,:bucket,:key)
            ON CONFLICT(erasure_operation_id,bucket,object_key) DO NOTHING
            """).param("id",UUID.randomUUID()).param("operation",operation).param("school",schoolId)
            .param("student",studentId).param("bucket",target.bucket()).param("key",target.key()).update();
    }
    public record Work(UUID id, UUID token, long schoolId, long studentId, String bucket, String objectKey, Long generation) {
        public CleanupTarget target() { return new CleanupTarget(bucket,objectKey); }
    }
    public Optional<Work> claim() {
        return jdbc.sql("""
            WITH candidate AS (SELECT id FROM tenant_school.photo_cleanup_outbox
              WHERE (state='PENDING' OR (state='LEASED' AND lease_until<now())) AND next_attempt_at<=now()
              ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE tenant_school.photo_cleanup_outbox q SET state='LEASED',lease_token=gen_random_uuid(),
              lease_until=now()+interval '60 seconds',attempts=attempts+1
            FROM candidate c WHERE q.id=c.id
            RETURNING q.id,q.lease_token,q.school_id,q.student_id,q.bucket,q.object_key,q.object_generation
            """).query((rs,n)->new Work(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getObject(7,Long.class))).optional();
    }
    public boolean pin(Work work, long generation) {
        return jdbc.sql("""
            UPDATE tenant_school.photo_cleanup_outbox SET object_generation=:generation
            WHERE id=:id AND lease_token=:token AND state='LEASED' AND lease_until>now() AND object_generation IS NULL
            """).param("generation",generation).param("id",work.id()).param("token",work.token()).update()==1;
    }
    public boolean sourceIdentityReused(Work work) {
        jdbc.sql("SELECT set_config('app.current_school_id',:school,true)").param("school",Long.toString(work.schoolId())).query(String.class).single();
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM student.students WHERE id=:student AND school_id=:school)")
                .param("student",work.studentId()).param("school",work.schoolId()).query(Boolean.class).single();
    }
    public boolean fenced(Work work) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM tenant_school.photo_cleanup_outbox WHERE id=:id AND lease_token=:token AND state='LEASED' AND lease_until>now())")
                .param("id",work.id()).param("token",work.token()).query(Boolean.class).single();
    }
    public boolean complete(Work work) { return transition(work,"DONE",null); }
    public void block(Work work) { transition(work,"BLOCKED","SOURCE_IDENTITY_REUSED"); }
    private boolean transition(Work work,String state,String error) {
        return jdbc.sql("UPDATE tenant_school.photo_cleanup_outbox SET state=:state,completed_at=now(),lease_token=NULL,lease_until=NULL,last_error=:error WHERE id=:id AND lease_token=:token AND state='LEASED' AND lease_until>now()")
                .param("state",state).param("error",error,java.sql.Types.VARCHAR).param("id",work.id()).param("token",work.token()).update()==1;
    }
    public void retry(Work work) {
        jdbc.sql("""
            UPDATE tenant_school.photo_cleanup_outbox SET state='PENDING',lease_token=NULL,lease_until=NULL,
              last_error='OBJECT_CLEANUP_RETRY',next_attempt_at=now()+make_interval(secs=>LEAST(3600,10*(1<<LEAST(attempts,8))))
            WHERE id=:id AND lease_token=:token AND state='LEASED'
            """).param("id",work.id()).param("token",work.token()).update();
    }
}
