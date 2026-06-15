package com.custoking.ims.model;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Standard paginated response envelope returned by all paginated list endpoints.
 * <p>
 * Serialises to:
 * <pre>
 * {
 *   "content":       [...],
 *   "page":          0,
 *   "size":          20,
 *   "totalElements": 150,
 *   "totalPages":    8,
 *   "last":          false
 * }
 * </pre>
 *
 * @param <T> the type of items in {@code content}
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    /**
     * Convenience factory — wraps a Spring Data {@link Page} whose content
     * has already been mapped to the desired DTO type.
     */
    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(
                p.getContent(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.getTotalPages(),
                p.isLast()
        );
    }
}
