package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.SellerStatus;

public record SellerResponse(UUID id, String name, String phone, int commissionRate,
        SellerStatus status, Instant createdAt, Instant updatedAt) {
}