package br.com.luma.auth.dto;

import br.com.luma.shared.dto.UserResponse;

public record AuthResponse(UserResponse user, SessionResponse session, String accessToken, String refreshToken) {
}