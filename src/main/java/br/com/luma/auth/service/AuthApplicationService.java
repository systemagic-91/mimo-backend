package br.com.luma.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.luma.auth.dto.AuthResponse;
import br.com.luma.auth.dto.AuthSessionResponse;
import br.com.luma.auth.dto.ChangePasswordRequest;
import br.com.luma.auth.dto.LoginRequest;
import br.com.luma.auth.dto.SessionResponse;
import br.com.luma.auth.entity.RefreshTokenEntity;
import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.exception.AuthException;
import br.com.luma.auth.mapper.UserMapper;
import br.com.luma.auth.repository.RefreshTokenRepository;
import br.com.luma.auth.repository.UserRepository;

@Service
public class AuthApplicationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenHasher refreshTokenHasher;
    private final AccessTokenService accessTokenService;
    private final UserMapper userMapper;
    private final AuthenticationAuditService auditService;
    private final LoginRateLimiter loginRateLimiter;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final String dummyPasswordHash;

    public AuthApplicationService(UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenHasher refreshTokenHasher,
            AccessTokenService accessTokenService,
            UserMapper userMapper,
            AuthenticationAuditService auditService,
            LoginRateLimiter loginRateLimiter,
            Clock clock,
            @Value("${app.security.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenHasher = refreshTokenHasher;
        this.accessTokenService = accessTokenService;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String clientIp) {
        loginRateLimiter.acquire(clientIp);
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (!passwordMatches) {
            auditService.recordLogin(user == null ? null : user.getId(), "LOGIN_FAILED", "INVALID_CREDENTIALS", clientIp);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email ou senha inválidos.");
        }
        if (!user.isActive()) {
            auditService.recordLogin(user.getId(), "LOGIN_FAILED", "USER_INACTIVE", clientIp);
            throw new AuthException(HttpStatus.FORBIDDEN, "USER_INACTIVE", "Usuário inativo.");
        }

        loginRateLimiter.recordSuccess(clientIp);
        auditService.recordLogin(user.getId(), "LOGIN_SUCCEEDED", "SUCCESS", clientIp);
        return issueSession(user, clock.instant());
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String hash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshTokenEntity oldToken = refreshTokenRepository.findForUpdateByTokenHash(hash)
                .orElseThrow(AuthApplicationService::invalidRefreshToken);
        UserEntity user = oldToken.getUser();

        if (oldToken.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
            auditService.recordLogin(user.getId(), "REFRESH_TOKEN_REUSE", "REVOKED_ALL_SESSIONS", null);
            throw invalidRefreshToken();
        }
        if (!oldToken.getExpiresAt().isAfter(now)) {
            oldToken.setRevokedAt(now);
            throw invalidRefreshToken();
        }
        if (!user.isActive()) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
            throw invalidRefreshToken();
        }

        oldToken.setRevokedAt(now);
        SessionTokens replacement = createSessionTokens(user, now);
        oldToken.setReplacedBy(replacement.refreshTokenEntity());
        refreshTokenRepository.save(oldToken);
        return toResponse(user, replacement);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void logout(String rawRefreshToken, UUID authenticatedUserId) {
        refreshTokenRepository.findForUpdateByTokenHash(refreshTokenHasher.hash(rawRefreshToken))
                .filter(token -> token.getUser().getId().equals(authenticatedUserId))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(clock.instant());
                    refreshTokenRepository.save(token);
                });
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public AuthSessionResponse currentUser(UUID userId, Instant issuedAt, Instant expiresAt) {
        UserEntity user = userRepository.findById(userId)
                .filter(UserEntity::isActive)
                .orElseThrow(AuthApplicationService::invalidAccessToken);
        return new AuthSessionResponse(userMapper.toResponse(user), new SessionResponse(issuedAt, expiresAt));
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void changePassword(UUID userId, ChangePasswordRequest request, String clientIp) {
        UserEntity user = userRepository.findById(userId)
                .filter(UserEntity::isActive)
                .orElseThrow(AuthApplicationService::invalidAccessToken);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditService.recordLogin(user.getId(), "PASSWORD_CHANGE_FAILED", "INVALID_CURRENT_PASSWORD", clientIp);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Credenciais inválidas.");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "A nova senha deve ser diferente da atual.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.saveAndFlush(user);
        refreshTokenRepository.revokeAllActiveByUserId(userId, clock.instant());
        auditService.recordLogin(userId, "PASSWORD_CHANGED", "SUCCESS", clientIp);
    }

    private AuthResponse issueSession(UserEntity user, Instant now) {
        return toResponse(user, createSessionTokens(user, now));
    }

    private SessionTokens createSessionTokens(UserEntity user, Instant now) {
        AccessTokenService.IssuedAccessToken accessToken = accessTokenService.issue(user, now);
        byte[] randomToken = new byte[32];
        SECURE_RANDOM.nextBytes(randomToken);
        String rawRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken);

        RefreshTokenEntity refreshToken = new RefreshTokenEntity();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(refreshTokenHasher.hash(rawRefreshToken));
        refreshToken.setCreatedAt(now);
        refreshToken.setExpiresAt(now.plus(refreshTokenTtl));
        refreshTokenRepository.save(refreshToken);
        return new SessionTokens(accessToken, rawRefreshToken, refreshToken);
    }

    private AuthResponse toResponse(UserEntity user, SessionTokens tokens) {
        return new AuthResponse(userMapper.toResponse(user),
                new SessionResponse(tokens.accessToken().issuedAt(), tokens.accessToken().expiresAt()),
                tokens.accessToken().value(), tokens.rawRefreshToken());
    }

    private static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token inválido ou expirado.");
    }

    private static AuthException invalidAccessToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida ou expirada.");
    }

    private record SessionTokens(AccessTokenService.IssuedAccessToken accessToken,
            String rawRefreshToken, RefreshTokenEntity refreshTokenEntity) {
    }
}