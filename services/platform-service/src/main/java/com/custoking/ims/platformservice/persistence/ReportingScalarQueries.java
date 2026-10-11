package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Scalar SQL reads using the caller's scoped data source. */
@Repository
public class ReportingScalarQueries {
    private final JdbcClient jdbc;

    public ReportingScalarQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    long count(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    long count(String sql, String yearId) {
        return jdbc.sql(sql).param("yearId", yearId).query(Long.class).single();
    }

    long count(String sql, String yearId, Long schoolId) {
        return jdbc.sql(sql).param("yearId", yearId).param("schoolId", schoolId).query(Long.class).single();
    }

    long countAmount(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    long countAmount(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    long countAmount(String sql, String paramName, String value) {
        return jdbc.sql(sql).param(paramName, value).query(Long.class).single();
    }

    long countAmount(String sql, String yearId, Long schoolId) {
        return jdbc.sql(sql).param("yearId", yearId).param("schoolId", schoolId).query(Long.class).single();
    }
}
