package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.PieceStage;

public record PieceResponse(UUID id, String code, String type, long valueCents, PieceStage status,
        String currentLocation, String photoUrl, String note, Instant createdAt, Instant updatedAt, long version) {
}