package br.com.luma.shared.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import br.com.luma.shared.ConsignmentItemStatus;

// paymentDueDate e amountDueCents só existem para itens vendidos de lotes com prazo; amountDueCents é null para staff.
public record ConsignmentItemResponse(UUID id, UUID consignmentId, UUID pieceId, String codeSnapshot,
        String typeSnapshot, long valueCentsSnapshot, int commissionRateSnapshot,
        ConsignmentItemStatus status, Instant soldAt, Instant returnedAt, LocalDate paymentDueDate,
        Long amountDueCents, long version) {

    public ConsignmentItemResponse withPayment(LocalDate dueDate, Long amountDue) {
        return new ConsignmentItemResponse(id, consignmentId, pieceId, codeSnapshot, typeSnapshot, valueCentsSnapshot,
                commissionRateSnapshot, status, soldAt, returnedAt, dueDate, amountDue, version);
    }
}
