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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import br.com.luma.auth.dto.AuthResponse;
import br.com.luma.auth.dto.AuthSessionResponse;
import br.com.luma.auth.dto.ChangePasswordRequest;
import br.com.luma.auth.dto.LoginRequest;
import br.com.luma.auth.exception.AuthException;
import br.com.luma.auth.service.AuthApplicationService;
import br.com.luma.auth.service.AuthApplicationService.IssuedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Login, sessões e gerenciamento de credenciais.")
public class AuthController {

    private final AuthApplicationService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final OriginGuard originGuard;

    public AuthController(AuthApplicationService authService, RefreshTokenCookie refreshTokenCookie,
            OriginGuard originGuard) {
        this.authService = authService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.originGuard = originGuard;
    }

    @PostMapping("/login")
    @Operation(summary = "Inicia uma sessão", description = "O refresh token vai só no cookie HttpOnly luma_refresh.")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return withCookie(authService.login(request, httpRequest.getRemoteAddr()), httpResponse);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotaciona os tokens de sessão a partir do cookie",
            parameters = @Parameter(in = ParameterIn.COOKIE, name = "luma_refresh", required = true))
    public AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        originGuard.check(httpRequest);
        String refreshToken = refreshTokenCookie.read(httpRequest).orElse(null);
        try {
            if (refreshToken == null) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Refresh token inválido ou expirado.");
            }
            return withCookie(authService.refresh(refreshToken), httpResponse);
        } catch (AuthException exception) {
            refreshTokenCookie.clear(httpResponse);
            throw exception;
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoga a sessão do cookie e apaga o cookie",
            parameters = @Parameter(in = ParameterIn.COOKIE, name = "luma_refresh"))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        originGuard.check(httpRequest);
        refreshTokenCookie.read(httpRequest).ifPresent(authService::logout);
        refreshTokenCookie.clear(httpResponse);
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
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.changePassword(UUID.fromString(jwt.getSubject()), request, httpRequest.getRemoteAddr());
        refreshTokenCookie.clear(httpResponse);
    }

    private AuthResponse withCookie(IssuedSession session, HttpServletResponse httpResponse) {
        refreshTokenCookie.write(httpResponse, session.refreshToken());
        return session.response();
    }
}
