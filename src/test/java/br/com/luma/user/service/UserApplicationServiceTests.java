package br.com.luma.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.mapper.UserMapper;
import br.com.luma.auth.repository.RefreshTokenRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.Role;
import br.com.luma.shared.audit.AuditTrail;
import br.com.luma.shared.dto.UserResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.user.dto.CreateUserRequest;
import br.com.luma.user.dto.ResetPasswordRequest;
import br.com.luma.user.dto.UpdateUserRequest;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final String IP = "203.0.113.10";
    private static final String TEMPORARY_PASSWORD = "Temporaria-Senha-1";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuditTrail auditTrail;
    @Mock
    private IdempotencyService idempotencyService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private UserEntity actor;
    private UserApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserApplicationService(userRepository, refreshTokenRepository, passwordEncoder, userMapper,
                auditTrail, idempotencyService, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        actor = user(Role.OWNER, true);
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(UUID.randomUUID());
            }
            return user;
        });
        lenient().when(userMapper.toResponse(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(),
                    user.isMustChangePassword(), null, null);
        });
    }

    @Test
    void createsActiveUserThatMustChangeTheTemporaryPassword() {
        IdempotentResponse response = service.create(actor.getId(), "key",
                new CreateUserRequest(" Bia ", " Bia@Loja.com ", Role.STAFF, TEMPORARY_PASSWORD), IP);

        assertThat(response.status()).isEqualTo(201);
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(saved.capture());
        UserEntity user = saved.getValue();
        assertThat(user.getName()).isEqualTo("Bia");
        assertThat(user.getEmail()).isEqualTo("bia@loja.com");
        assertThat(user.isActive()).isTrue();
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches(TEMPORARY_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(response.body().toString()).doesNotContain(TEMPORARY_PASSWORD).doesNotContain("password\"");
        assertAudit("USER_CREATED", user.getId());
    }

    @Test
    void temporaryPasswordNeverReachesTheIdempotencyFingerprint() {
        service.create(actor.getId(), "key",
                new CreateUserRequest("Bia", "bia@loja.com", Role.STAFF, TEMPORARY_PASSWORD), IP);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(idempotencyService).execute(eq(actor.getId()), eq("users.create"), eq("key"), payload.capture(), any());
        assertThat(payload.getValue().toString()).doesNotContain(TEMPORARY_PASSWORD);
    }

    @Test
    void emailMustBeUniqueIgnoringCase() {
        UserEntity existing = user(Role.STAFF, true);
        when(userRepository.findByEmailIgnoreCase("bia@loja.com")).thenReturn(Optional.of(existing));

        assertApiError(() -> service.create(actor.getId(), "key",
                new CreateUserRequest("Bia", "BIA@loja.com", Role.STAFF, TEMPORARY_PASSWORD), IP),
                HttpStatus.CONFLICT, "EMAIL_ALREADY_USED");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void changingEmailToAnotherUsersEmailFails() {
        UserEntity target = target(Role.STAFF, true);
        UserEntity other = user(Role.STAFF, true);
        when(userRepository.findByEmailIgnoreCase("outra@loja.com")).thenReturn(Optional.of(other));

        assertApiError(() -> service.update(actor.getId(), target.getId(), "key",
                new UpdateUserRequest(null, "Outra@Loja.com", null, null), IP),
                HttpStatus.CONFLICT, "EMAIL_ALREADY_USED");
    }

    @Test
    void editingNameAndEmailKeepsTheSessionAlive() {
        UserEntity target = target(Role.STAFF, true);
        when(userRepository.findByEmailIgnoreCase("nova@loja.com")).thenReturn(Optional.of(target));

        service.update(actor.getId(), target.getId(), "key",
                new UpdateUserRequest(" Nova ", "Nova@Loja.com", null, null), IP);

        assertThat(target.getName()).isEqualTo("Nova");
        assertThat(target.getEmail()).isEqualTo("nova@loja.com");
        assertThat(target.getAuthVersion()).isZero();
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());
        verify(userRepository, never()).findActiveByRoleForUpdate(any());
        assertAudit("USER_UPDATED", target.getId());
    }

    @Test
    void deactivatingLogsTheUserOutImmediately() {
        UserEntity target = target(Role.STAFF, true);
        when(userRepository.findActiveByRoleForUpdate(Role.OWNER)).thenReturn(List.of(actor));

        service.update(actor.getId(), target.getId(), "key", new UpdateUserRequest(null, null, null, false), IP);

        assertThat(target.isActive()).isFalse();
        assertThat(target.getAuthVersion()).isEqualTo(1);
        verify(refreshTokenRepository).revokeAllActiveByUserId(target.getId(), NOW);
    }

    @Test
    void changingRoleLogsTheUserOutImmediately() {
        UserEntity target = target(Role.STAFF, true);

        service.update(actor.getId(), target.getId(), "key",
                new UpdateUserRequest(null, null, Role.OWNER, null), IP);

        assertThat(target.getRole()).isEqualTo(Role.OWNER);
        assertThat(target.getAuthVersion()).isEqualTo(1);
        verify(refreshTokenRepository).revokeAllActiveByUserId(target.getId(), NOW);
    }

    @Test
    void reactivatingDoesNotNeedToTouchSessions() {
        UserEntity target = target(Role.STAFF, false);

        service.update(actor.getId(), target.getId(), "key", new UpdateUserRequest(null, null, null, true), IP);

        assertThat(target.isActive()).isTrue();
        assertThat(target.getAuthVersion()).isZero();
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());
    }

    @Test
    void ownerCannotDeactivateThemselves() {
        when(userRepository.findActiveByRoleForUpdate(Role.OWNER)).thenReturn(List.of(actor));
        when(userRepository.findByIdForUpdate(actor.getId())).thenReturn(Optional.of(actor));

        assertApiError(() -> service.update(actor.getId(), actor.getId(), "key",
                new UpdateUserRequest(null, null, null, false), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_DEACTIVATE_SELF");
        assertThat(actor.isActive()).isTrue();
    }

    @Test
    void ownerCannotChangeTheirOwnRoleButCanRenameThemselves() {
        when(userRepository.findActiveByRoleForUpdate(Role.OWNER)).thenReturn(List.of(actor, user(Role.OWNER, true)));
        when(userRepository.findByIdForUpdate(actor.getId())).thenReturn(Optional.of(actor));

        assertApiError(() -> service.update(actor.getId(), actor.getId(), "key",
                new UpdateUserRequest(null, null, Role.STAFF, null), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_CHANGE_OWN_ROLE");

        service.update(actor.getId(), actor.getId(), "key2",
                new UpdateUserRequest("Novo nome", null, Role.OWNER, true), IP);
        assertThat(actor.getName()).isEqualTo("Novo nome");
        assertThat(actor.getAuthVersion()).isZero();
    }

    @Test
    void storeNeverRunsOutOfActiveOwners() {
        UserEntity target = target(Role.OWNER, true);
        // Outro owner desativou quem está agindo ao mesmo tempo: só o alvo sobrou entre os owners ativos travados.
        when(userRepository.findActiveByRoleForUpdate(Role.OWNER)).thenReturn(List.of(target));

        assertApiError(() -> service.update(actor.getId(), target.getId(), "key",
                new UpdateUserRequest(null, null, null, false), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "LAST_OWNER");
        assertApiError(() -> service.update(actor.getId(), target.getId(), "key",
                new UpdateUserRequest(null, null, Role.STAFF, null), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "LAST_OWNER");
        assertThat(target.isActive()).isTrue();
        assertThat(target.getRole()).isEqualTo(Role.OWNER);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void ownerCanRemoveAnotherOwnerWhileOthersRemain() {
        UserEntity target = target(Role.OWNER, true);
        when(userRepository.findActiveByRoleForUpdate(Role.OWNER)).thenReturn(List.of(actor, target));

        service.update(actor.getId(), target.getId(), "key", new UpdateUserRequest(null, null, Role.STAFF, false), IP);

        assertThat(target.getRole()).isEqualTo(Role.STAFF);
        assertThat(target.isActive()).isFalse();
        assertThat(target.getAuthVersion()).isEqualTo(1);
    }

    @Test
    void updateRequiresAtLeastOneField() {
        assertApiError(() -> service.update(actor.getId(), UUID.randomUUID(), "key",
                new UpdateUserRequest(null, null, null, null), IP),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    void unknownUserIsNotFound() {
        UUID missing = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.resetPassword(actor.getId(), missing, "key",
                new ResetPasswordRequest(TEMPORARY_PASSWORD), IP), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void resetPasswordForcesAChangeAndLogsTheUserOut() {
        UserEntity target = target(Role.STAFF, true);

        IdempotentResponse response = service.resetPassword(actor.getId(), target.getId(), "key",
                new ResetPasswordRequest(TEMPORARY_PASSWORD), IP);

        assertThat(passwordEncoder.matches(TEMPORARY_PASSWORD, target.getPasswordHash())).isTrue();
        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(target.getAuthVersion()).isEqualTo(1);
        verify(refreshTokenRepository).revokeAllActiveByUserId(target.getId(), NOW);
        assertThat(response.body().toString()).doesNotContain(TEMPORARY_PASSWORD);
        assertAudit("USER_PASSWORD_RESET", target.getId());
        verify(idempotencyService).execute(eq(actor.getId()), eq("users.reset-password." + target.getId()),
                eq("key"), eq(Map.of()), any());
    }

    @Test
    void ownerCannotResetTheirOwnPassword() {
        when(userRepository.findByIdForUpdate(actor.getId())).thenReturn(Optional.of(actor));

        assertApiError(() -> service.resetPassword(actor.getId(), actor.getId(), "key",
                new ResetPasswordRequest(TEMPORARY_PASSWORD), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_RESET_OWN_PASSWORD");
        assertThat(actor.isMustChangePassword()).isFalse();
    }

    @Test
    void listRejectsUnknownRole() {
        assertApiError(() -> service.list(null, "admin", null, new br.com.luma.shared.dto.PaginationRequest()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    private void assertAudit(String action, UUID userId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(auditTrail).record(eq(actor.getId()), eq(action), eq("USER"), eq(userId), eq(IP), state.capture());
        assertThat(state.getValue()).containsOnlyKeys("role", "active", "mustChangePassword");
    }

    private UserEntity target(Role role, boolean active) {
        UserEntity target = user(role, active);
        when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
        return target;
    }

    private UserEntity user(Role role, boolean active) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setName("Pessoa");
        user.setEmail(UUID.randomUUID() + "@loja.com");
        user.setRole(role);
        user.setActive(active);
        user.setPasswordHash(passwordEncoder.encode("Senha-Antiga-123"));
        return user;
    }

    private static void assertApiError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }
}
