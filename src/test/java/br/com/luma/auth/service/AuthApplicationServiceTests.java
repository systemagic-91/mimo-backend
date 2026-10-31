package br.com.luma.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import br.com.luma.auth.dto.AuthResponse;
import br.com.luma.auth.dto.ChangePasswordRequest;
import br.com.luma.auth.dto.LoginRequest;
import br.com.luma.auth.entity.RefreshTokenEntity;
import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.exception.AuthException;
import br.com.luma.auth.mapper.UserMapper;
import br.com.luma.auth.repository.RefreshTokenRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.Role;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String PASSWORD = "Correct-Password-1";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private AccessTokenService accessTokenService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuthenticationAuditService auditService;
    @Mock
    private LoginRateLimiter loginRateLimiter;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final RefreshTokenHasher refreshTokenHasher = new RefreshTokenHasher();
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AuthApplicationService(userRepository, refreshTokenRepository, passwordEncoder,
                refreshTokenHasher, accessTokenService, userMapper, auditService, loginRateLimiter,
                Clock.fixed(NOW, ZoneOffset.UTC), REFRESH_TTL);
    }

    @Test
    void loginNormalizesEmailAndIssuesHashedRefreshToken() {
        UserEntity user = user(true);
        when(userRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));
        stubAccessToken(user);

        AuthResponse response = service.login(new LoginRequest(" Owner@Example.com ", PASSWORD), CLIENT_IP);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
        ArgumentCaptor<RefreshTokenEntity> saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(refreshTokenHasher.hash(response.refreshToken()))
                .isNotEqualTo(response.refreshToken());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(REFRESH_TTL));
        verify(loginRateLimiter).recordSuccess(CLIENT_IP);
        verify(auditService).recordLogin(user.getId(), "LOGIN_SUCCEEDED", "SUCCESS", CLIENT_IP);
    }

    @Test
    void unknownEmailAndWrongPasswordReturnTheSameGenericError() {
        UserEntity user = user(true);
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));

        assertApiError(() -> service.login(new LoginRequest("missing@example.com", PASSWORD), CLIENT_IP),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        assertApiError(() -> service.login(new LoginRequest("owner@example.com", "Wrong-Password-1"), CLIENT_IP),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");

        verify(auditService).recordLogin(isNull(), eq("LOGIN_FAILED"), eq("INVALID_CREDENTIALS"), eq(CLIENT_IP));
        verify(auditService).recordLogin(user.getId(), "LOGIN_FAILED", "INVALID_CREDENTIALS", CLIENT_IP);
        verify(refreshTokenRepository, never()).save(any());
        verify(loginRateLimiter, never()).recordSuccess(any());
    }

    @Test
    void inactiveUserCannotLogIn() {
        UserEntity user = user(false);
        when(userRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));

        assertApiError(() -> service.login(new LoginRequest("owner@example.com", PASSWORD), CLIENT_IP),
                HttpStatus.FORBIDDEN, "USER_INACTIVE");
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rateLimitedLoginDoesNotCheckCredentials() {
        doThrow(new AuthException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Muitas tentativas."))
                .when(loginRateLimiter).acquire(CLIENT_IP);

        assertApiError(() -> service.login(new LoginRequest("owner@example.com", PASSWORD), CLIENT_IP),
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void refreshRotatesTokenAndLinksReplacement() {
        UserEntity user = user(true);
        RefreshTokenEntity oldToken = refreshToken(user, "old-refresh", NOW.plus(Duration.ofDays(1)), null);
        stubAccessToken(user);

        AuthResponse response = service.refresh("old-refresh");

        assertThat(response.refreshToken()).isNotEqualTo("old-refresh");
        assertThat(oldToken.getRevokedAt()).isEqualTo(NOW);
        assertThat(oldToken.getReplacedBy()).isNotNull();
        assertThat(oldToken.getReplacedBy().getTokenHash()).isEqualTo(refreshTokenHasher.hash(response.refreshToken()));
        verify(refreshTokenRepository).save(oldToken);
    }

    @Test
    void reusingRevokedRefreshTokenRevokesEverySession() {
        UserEntity user = user(true);
        refreshToken(user, "reused-refresh", NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(60));

        assertApiError(() -> service.refresh("reused-refresh"), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");

        verify(refreshTokenRepository).revokeAllActiveByUserId(user.getId(), NOW);
        verify(auditService).recordLogin(user.getId(), "REFRESH_TOKEN_REUSE", "REVOKED_ALL_SESSIONS", null);
    }

    @Test
    void expiredRefreshTokenIsRevokedAndRejected() {
        UserEntity user = user(true);
        RefreshTokenEntity token = refreshToken(user, "expired-refresh", NOW, null);

        assertApiError(() -> service.refresh("expired-refresh"), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");

        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(accessTokenService, never()).issue(any(), any());
    }

    @Test
    void refreshForInactiveUserRevokesAllSessions() {
        UserEntity user = user(false);
        refreshToken(user, "inactive-refresh", NOW.plus(Duration.ofDays(1)), null);

        assertApiError(() -> service.refresh("inactive-refresh"), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");

        verify(refreshTokenRepository).revokeAllActiveByUserId(user.getId(), NOW);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        when(refreshTokenRepository.findForUpdateByTokenHash(any())).thenReturn(Optional.empty());

        assertApiError(() -> service.refresh("unknown"), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void logoutRevokesOnlyTheAuthenticatedUsersToken() {
        UserEntity owner = user(true);
        RefreshTokenEntity token = refreshToken(owner, "owner-refresh", NOW.plus(Duration.ofDays(1)), null);

        service.logout("owner-refresh", UUID.randomUUID());
        assertThat(token.getRevokedAt()).isNull();

        service.logout("owner-refresh", owner.getId());
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(refreshTokenRepository).save(token);
    }

    @Test
    void changingPasswordBumpsAuthVersionAndRevokesSessions() {
        UserEntity user = user(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.changePassword(user.getId(), new ChangePasswordRequest(PASSWORD, "Brand-New-Password-2"), CLIENT_IP);

        assertThat(passwordEncoder.matches("Brand-New-Password-2", user.getPasswordHash())).isTrue();
        assertThat(user.getAuthVersion()).isEqualTo(1);
        verify(userRepository).saveAndFlush(user);
        verify(refreshTokenRepository).revokeAllActiveByUserId(user.getId(), NOW);
        verify(auditService).recordLogin(user.getId(), "PASSWORD_CHANGED", "SUCCESS", CLIENT_IP);
    }

    @Test
    void changingPasswordRequiresCurrentPasswordAndANewValue() {
        UserEntity user = user(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertApiError(() -> service.changePassword(user.getId(),
                new ChangePasswordRequest("Wrong-Password-1", "Brand-New-Password-2"), CLIENT_IP),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        assertApiError(() -> service.changePassword(user.getId(),
                new ChangePasswordRequest(PASSWORD, PASSWORD), CLIENT_IP),
                HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED");

        assertThat(user.getAuthVersion()).isZero();
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());
    }

    @Test
    void currentUserRejectsInactiveAccounts() {
        UserEntity user = user(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertApiError(() -> service.currentUser(user.getId(), NOW, NOW.plusSeconds(900)),
                HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN");
    }

    private UserEntity user(boolean active) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setName("Owner");
        user.setEmail("owner@example.com");
        user.setRole(Role.OWNER);
        user.setActive(active);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return user;
    }

    private RefreshTokenEntity refreshToken(UserEntity user, String rawToken, Instant expiresAt, Instant revokedAt) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenHash(refreshTokenHasher.hash(rawToken));
        token.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        when(refreshTokenRepository.findForUpdateByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        return token;
    }

    private void stubAccessToken(UserEntity user) {
        when(accessTokenService.issue(user, NOW)).thenReturn(
                new AccessTokenService.IssuedAccessToken("access-token", NOW, NOW.plusSeconds(900)));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuthException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
