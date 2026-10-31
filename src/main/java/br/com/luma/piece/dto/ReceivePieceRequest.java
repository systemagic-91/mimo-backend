package br.com.luma.piece.dto;

import br.com.luma.shared.PieceStage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReceivePieceRequest(
        @NotBlank @Pattern(regexp = "(?:Brinco|Colar|Pulseira|Anel|Pingente|Tornozeleira)")
        @Size(max = 80) String type,
        @Positive long valueCents,
        @NotNull PieceStage stage,
        @Size(max = 500) String note,
        @PositiveOrZero Long factoryCostCents) {
}
