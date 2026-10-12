package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SettingsResponse(UUID id, int commissionMin, int commissionMax, int commissionStep,
        int defaultCommissionRate, List<Integer> paymentTerms, int defaultConsignmentDays,
        String timezone, Instant updatedAt, UUID updatedById) {
}