package br.com.luma.shared.dto;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageEnvelope<T>(List<T> items, PaginationMetadata pagination) {

    public static <T> PageEnvelope<T> from(Page<T> page) {
        return new PageEnvelope<>(page.getContent(), new PaginationMetadata(
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()));
    }
}