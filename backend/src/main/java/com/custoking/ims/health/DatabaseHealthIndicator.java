package com.custoking.ims.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;

    public DatabaseHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Long result = jdbc.queryForObject("SELECT 1", Long.class);
            if (result != null && result == 1L) {
                return Health.up().withDetail("db", "PostgreSQL").build();
            }
            return Health.down().withDetail("reason", "unexpected ping result").build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
