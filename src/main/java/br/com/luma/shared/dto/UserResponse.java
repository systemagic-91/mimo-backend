package br.com.luma.shared.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.luma.shared.Role;

public record UserResponse(UUID id, String name, String email, Role role, boolean active,
        boolean mustChangePassword,
        Instant createdAt, Instant updatedAt) {
}