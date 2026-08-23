package com.custoking.ims.platformservice;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;

/** Test-only lifecycle helper that closes a Hikari pool even when it is wrapped. */
public final class TestDataSources {

    private TestDataSources() {
    }

    public static void close(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof DelegatingDataSource delegating) {
            current = delegating.getTargetDataSource();
        }
        if (current instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
