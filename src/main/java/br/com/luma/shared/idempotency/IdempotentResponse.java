package br.com.luma.shared.idempotency;

import com.fasterxml.jackson.databind.JsonNode;

public record IdempotentResponse(int status, JsonNode body) {
}