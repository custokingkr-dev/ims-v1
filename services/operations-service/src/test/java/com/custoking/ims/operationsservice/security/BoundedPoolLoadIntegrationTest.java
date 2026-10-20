package com.custoking.ims.operationsservice.security;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Local bounded capacity measurement, not a certification of cloud limits or a production benchmark. */
class BoundedPoolLoadIntegrationTest {
    @Test void concurrentQueriesAndCancellationRespectPhysicalPoolBudget() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            try(var pool=new HikariDataSource()) {
                pool.setJdbcUrl(pg.getJdbcUrl());pool.setUsername(pg.getUsername());pool.setPassword(pg.getPassword());
                pool.setMaximumPoolSize(3);pool.setMinimumIdle(0);pool.setConnectionTimeout(1000);
                pool.addDataSourceProperty("ApplicationName","security-bounded-load");
                AtomicInteger peak=new AtomicInteger();List<Long> latencies=Collections.synchronizedList(new ArrayList<>());
                long started=System.nanoTime();long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
                try(var workers=Executors.newFixedThreadPool(8)) {
                    List<Future<?>> requests=new ArrayList<>();
                    for(int i=0;i<32;i++) requests.add(workers.submit(() -> {
                        long began=System.nanoTime();
                        try(var connection=pool.getConnection();var statement=connection.createStatement()) {
                            peak.accumulateAndGet(pool.getHikariPoolMXBean().getTotalConnections(),Math::max);
                            statement.setQueryTimeout(1);try(var rows=statement.executeQuery("SELECT pg_sleep(0.025), 1")) {assertThat(rows.next()).isTrue();assertThat(rows.getInt(2)).isEqualTo(1);}
                        } catch(Exception ex) {throw new RuntimeException(ex);} finally {latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));}
                    }));
                    for(var request:requests)request.get(5,TimeUnit.SECONDS);
                }
                long elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertThat(peak.get()).isBetween(1,3);assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                long cancelledStarted=System.nanoTime();
                try(var connection=pool.getConnection();var statement=connection.createStatement()) {
                    statement.setQueryTimeout(1);assertThatThrownBy(() -> statement.execute("SELECT pg_sleep(5)")).isInstanceOf(java.sql.SQLException.class);
                }
                long cancelledMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cancelledStarted);
                assertThat(cancelledMs).isLessThan(3000);assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                try(var connection=pool.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='security-bounded-load'")) {
                    rows.next();assertThat(rows.getInt(1)).isLessThanOrEqualTo(3);
                }
                Collections.sort(latencies);long heapAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
                System.out.println("IMS_BOUNDED_POOL_EVIDENCE|{\"requests\":32,\"workers\":8,\"poolMax\":3,\"observedPhysicalPoolPeak\":"+peak.get()+",\"durationMs\":"+elapsedMs+",\"p95Ms\":"+latencies.get(30)+",\"cancelledQueryMs\":"+cancelledMs+",\"heapDeltaBytes\":"+(heapAfter-heapBefore)+",\"activeConnectionsAfter\":0,\"environment\":\"isolated-local-postgresql16\"}");
            }
        }
    }
}
