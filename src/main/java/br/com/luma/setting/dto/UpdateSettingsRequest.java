package br.com.luma.setting.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateSettingsRequest(
        @NotNull @Min(0) @Max(100) Integer defaultCommissionRate,
        @NotEmpty @Size(max = 3) List<@NotNull @Min(1) Integer> paymentTerms,
        @NotNull @Min(1) Integer defaultConsignmentDays,
        @NotBlank @Size(max = 64) String timezone) {
}