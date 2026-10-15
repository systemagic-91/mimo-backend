package br.com.luma.shared.dto;

public record PaginationMetadata(int page, int pageSize, long total, int totalPages) {
}