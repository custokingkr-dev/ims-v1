package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class CatalogDefinitionRequests {
    private CatalogDefinitionRequests() {}
    private static final String CODE = "^[A-Z][A-Z0-9_]{1,59}$";
    private static final String LABEL = ".*\\S.*";

    public record Category(
            @Pattern(regexp = CODE) @Size(max = 40) String code,
            @Pattern(regexp = LABEL) @Size(max = 80) String label,
            @Size(max = 16) String emoji, @Size(max = 200) String description,
            @Pattern(regexp = "Recurring|One-time|Service") String orderType,
            Boolean formEnabled, Integer sortOrder, Boolean active) {}

    public record Group(
            @Pattern(regexp = CODE) @Size(max = 40) String categoryCode,
            @Pattern(regexp = CODE) @Size(max = 40) String code,
            @Pattern(regexp = LABEL) @Size(max = 80) String label,
            @Positive Integer level, @Pattern(regexp = "SINGLE") String selectionType,
            Boolean required, @Pattern(regexp = "ORDER|LINE") String scope, Boolean active) {}

    public record Option(
            @Positive Long groupId, @Pattern(regexp = CODE) @Size(max = 60) String code,
            @Pattern(regexp = LABEL) @Size(max = 120) String label, @Size(max = 120) String specText,
            @Positive Integer widthMm, @Positive Integer heightMm,
            @Pattern(regexp = "CONFIRMED|PENDING_SPEC") String specStatus, Integer sortOrder, Boolean active) {}

    public record Rule(
            @Pattern(regexp = CODE) @Size(max = 40) String categoryCode,
            @Pattern(regexp = "REQUIRE_QUANTITY_TOTAL|ROUND_TO_MULTIPLE|MIN_VALUE|MAX_VALUE|REQUIRE_ASSET") String ruleType,
            @Pattern(regexp = "BOOK_COUNT|PAGE_COUNT") String targetField,
            Map<String, Object> matchOptions, Map<String, Object> params, Integer priority,
            @Size(max = 200) String message, Boolean active) {}
}
