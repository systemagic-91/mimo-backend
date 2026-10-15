package br.com.luma.auth.dto;

import br.com.luma.shared.dto.UserResponse;

public record AuthSessionResponse(UserResponse user, SessionResponse session) {
}