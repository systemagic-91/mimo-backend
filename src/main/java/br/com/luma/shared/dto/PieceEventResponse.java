package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.PieceStage;

public record PieceEventResponse(UUID id, UUID pieceId, PieceStage stage, Instant occurredAt,
        String note, UUID actorUserId) {
}