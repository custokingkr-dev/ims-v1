package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** School calendar and localization reads from owned reporting dimensions. */
@Repository
public class ReportingSchoolContext {
    private final JdbcClient jdbc;

    public ReportingSchoolContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String activeAcademicYearId() {
        List<String> activeYears = jdbc.sql("SELECT id FROM reporting.dim_academic_year WHERE active = true LIMIT 1")
                .query(String.class)
                .list();
        return activeYears.isEmpty() ? null : activeYears.get(0);
    }

    String currentAcademicYearId(Long schoolId) {
        if (schoolId == null) {
            return activeAcademicYearId();
        }
        Optional<Integer> configuredStartMonth = configuredSchoolYearStartMonth(schoolId, "academic_year_start_month");
        if (configuredStartMonth.isEmpty()) {
            return activeAcademicYearId();
        }
        LocalDate today = LocalDate.now();
        int startMonth = configuredStartMonth.get();
        int startYear = today.getMonthValue() >= startMonth ? today.getYear() : today.getYear() - 1;
        int endYear = startYear + 1;
        return "ay_" + startYear + "_" + String.valueOf(endYear).substring(2);
    }

    int schoolYearStartMonth(Long schoolId, String column) {
        if (schoolId == null) {
            return 4;
        }
        return configuredSchoolYearStartMonth(schoolId, column).orElse(4);
    }

    Map<String, String> schoolLocalization(Long schoolId) {
        Map<String, String> defaults = Map.of(
                "timeZone", "Asia/Kolkata", "countryCode", "IN", "locale", "en-IN",
                "currencyCode", "INR", "phoneRegion", "IN");
        if (schoolId == null) return defaults;
        return jdbc.sql("""
                        SELECT time_zone, country_code, locale, currency_code, phone_region
                        FROM reporting.dim_school WHERE id = :schoolId
                        """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> Map.of(
                        "timeZone", rs.getString("time_zone"),
                        "countryCode", rs.getString("country_code"),
                        "locale", rs.getString("locale"),
                        "currencyCode", rs.getString("currency_code"),
                        "phoneRegion", rs.getString("phone_region")))
                .optional().orElse(defaults);
    }

    Optional<Integer> configuredSchoolYearStartMonth(Long schoolId, String column) {
        String sql = switch (column) {
            case "academic_year_start_month" -> """
                    SELECT academic_year_start_month
                    FROM reporting.dim_school
                    WHERE id = :schoolId
                    LIMIT 1
                    """;
            case "financial_year_start_month" -> """
                    SELECT financial_year_start_month
                    FROM reporting.dim_school
                    WHERE id = :schoolId
                    LIMIT 1
                    """;
            default -> throw new IllegalArgumentException("Unsupported year-start column: " + column);
        };
        return jdbc.sql(sql)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .filter(month -> month >= 1 && month <= 12);
    }
}
