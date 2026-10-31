package br.com.luma.piece.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record LabelPieceRequest(
        @NotNull UUID sourcePieceId,
        @NotBlank @Pattern(regexp = "(?:Brinco|Colar|Pulseira|Anel|Pingente|Tornozeleira)")
        @Size(max = 80) String type,
        @Positive long valueCents,
        UUID photoId,
        @Size(max = 500) String note,
        @NotNull Boolean backFromBath,
        @PositiveOrZero Long bathCostCents) {
}
