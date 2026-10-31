package br.com.luma.consignment.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.dto.SellerResponse;

public record ConsignmentListItemResponse(UUID id, String code, UUID sellerId, Instant deliveredAt,
        Instant dueAt, long dueInDays, String status, String note, UUID createdById, long version,
        SellerResponse seller, long piecesInHand, long valueInHandCents) {
}
