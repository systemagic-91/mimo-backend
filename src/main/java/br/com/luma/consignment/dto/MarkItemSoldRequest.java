package br.com.luma.consignment.dto;

import java.time.Instant;

import jakarta.validation.constraints.Size;

public record MarkItemSoldRequest(Instant soldAt, @Size(max = 500) String note) {
}
