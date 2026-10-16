package br.com.luma.seller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateSellerRequest(
        @Size(max = 120) String name,
        @Size(max = 40) String phone,
        @Min(0) @Max(100) Integer commissionRate,
        String status) {
}