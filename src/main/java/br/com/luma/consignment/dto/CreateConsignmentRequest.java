package br.com.luma.consignment.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateConsignmentRequest(
        @NotNull UUID sellerId,
        @NotNull @Size(max = 200) List<@NotNull UUID> pieceIds,
        @NotNull LocalDate dueDate,
        @NotNull Integer paymentTermDays,
        @Size(max = 500) String note) {
}
