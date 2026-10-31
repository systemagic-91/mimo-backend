package br.com.luma.user.dto;

import br.com.luma.shared.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull Role role,
        @NotBlank @Size(min = 12, max = 72) String temporaryPassword) {
}
