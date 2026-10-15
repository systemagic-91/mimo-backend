package br.com.luma.auth.dto;

import java.time.Instant;

public record SessionResponse(Instant loggedAt, Instant expiresAt) {
}