package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.ConsignmentStatus;

public record ConsignmentResponse(UUID id, String code, UUID sellerId, Instant deliveredAt,
        Instant dueAt, ConsignmentStatus status, String note, UUID createdById, long version) {
}