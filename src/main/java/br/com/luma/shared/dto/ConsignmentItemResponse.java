package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.ConsignmentItemStatus;

public record ConsignmentItemResponse(UUID id, UUID consignmentId, UUID pieceId, String codeSnapshot,
        String typeSnapshot, long valueCentsSnapshot, int commissionRateSnapshot,
        ConsignmentItemStatus status, Instant soldAt, Instant returnedAt, long version) {
}