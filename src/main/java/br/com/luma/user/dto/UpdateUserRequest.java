package br.com.luma.user.dto;

import br.com.luma.shared.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 120) String name,
        @Email @Size(max = 320) String email,
        Role role,
        Boolean active) {
}
