package br.com.luma.seller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSellerRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String phone,
        @NotNull @Min(0) @Max(100) Integer commissionRate) {
}