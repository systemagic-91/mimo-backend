package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.PieceStage;

// Os custos vêm sempre null para staff (ver PieceCostVisibility).
public record PieceResponse(UUID id, String code, String type, long valueCents, PieceStage status,
        String currentLocation, String photoUrl, String note, Long factoryCostCents, Long bathCostCents,
        Long totalCostCents, Instant createdAt, Instant updatedAt, long version) {

    public PieceResponse withoutCosts() {
        return new PieceResponse(id, code, type, valueCents, status, currentLocation, photoUrl, note, null, null, null,
                createdAt, updatedAt, version);
    }
}
