package com.gimble.bus_stop.dto.responses;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable JSON shape for paged lists (Spring's Page serialization is not a stable contract). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
