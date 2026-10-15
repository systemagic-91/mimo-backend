package br.com.luma.auth.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import br.com.luma.auth.dto.AuthResponse;
import br.com.luma.auth.dto.AuthSessionResponse;
import br.com.luma.auth.dto.ChangePasswordRequest;
import br.com.luma.auth.dto.LoginRequest;
import br.com.luma.auth.dto.LogoutRequest;
import br.com.luma.auth.dto.RefreshRequest;
import br.com.luma.auth.service.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Login, sessões e gerenciamento de credenciais.")
public class AuthController {

    private final AuthApplicationService authService;

    public AuthController(AuthApplicationService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Inicia uma sessão")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotaciona os tokens de sessão")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoga o refresh token atual", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(@Valid @RequestBody LogoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        authService.logout(request.refreshToken(), UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/me")
    @Operation(summary = "Retorna a identidade e a sessão atual", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("isAuthenticated()")
    public AuthSessionResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        return authService.currentUser(UUID.fromString(jwt.getSubject()), jwt.getIssuedAt(), jwt.getExpiresAt());
    }

    @PostMapping("/change-password")
    @Operation(summary = "Altera a senha e invalida as sessões anteriores", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        authService.changePassword(UUID.fromString(jwt.getSubject()), request, httpRequest.getRemoteAddr());
    }
}