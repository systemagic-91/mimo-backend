package br.com.luma.piece.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import br.com.luma.shared.dto.PaginationMetadata;
import br.com.luma.shared.dto.PieceResponse;

// Igual ao PageEnvelope, mais a soma do preço de todas as peças filtradas (não só as da página).
public record PieceListResponse(List<PieceResponse> items, PaginationMetadata pagination, Summary summary) {

    public static PieceListResponse from(Page<PieceResponse> page, long totalValueCents) {
        return new PieceListResponse(page.getContent(), new PaginationMetadata(page.getNumber() + 1, page.getSize(),
                page.getTotalElements(), page.getTotalPages()), new Summary(totalValueCents));
    }

    public record Summary(long totalValueCents) {
    }
}
