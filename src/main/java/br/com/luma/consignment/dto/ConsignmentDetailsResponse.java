package br.com.luma.consignment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.luma.shared.ConsignmentStatus;
import br.com.luma.shared.dto.ConsignmentItemResponse;
import br.com.luma.shared.dto.SellerResponse;

public record ConsignmentDetailsResponse(UUID id, String code, UUID sellerId, Instant deliveredAt,
        Instant dueAt, ConsignmentStatus status, boolean late, Integer paymentTermDays, String note,
        UUID createdById, long version,
        SellerResponse seller, List<ConsignmentItemResponse> items) {
}
