package br.com.luma.seller.dto;

import java.time.Instant;

import br.com.luma.shared.dto.SellerResponse;

public record SellerListItemResponse(SellerResponse seller, long piecesInHand, long valueInHandCents,
        Instant nextDueAt, Integer nextDueDays, boolean late, int openLots) {
}