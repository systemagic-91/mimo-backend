package br.com.luma.piece.dto;

import java.util.List;

import br.com.luma.shared.dto.PieceEventResponse;
import br.com.luma.shared.dto.PieceResponse;

public record ReceivePieceResponse(PieceResponse piece, List<PieceEventResponse> events) {
}
