package br.com.luma.user.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.mapper.UserMapper;
import br.com.luma.auth.repository.RefreshTokenRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.Role;
import br.com.luma.shared.audit.AuditTrail;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.UserResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.user.dto.CreateUserRequest;
import br.com.luma.user.dto.ResetPasswordRequest;
import br.com.luma.user.dto.UpdateUserRequest;

@Service
public class UserApplicationService {

    private static final Set<String> SORT_FIELDS = Set.of("name", "email", "role", "active", "createdAt", "updatedAt");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public UserApplicationService(UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            UserMapper userMapper,
            AuditTrail auditTrail,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.auditTrail = auditTrail;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public PageEnvelope<UserResponse> list(String rawQuery, String rawRole, Boolean active,
            PaginationRequest pagination) {
        String query = rawQuery == null || rawQuery.isBlank() ? null : rawQuery.trim();
        if (query != null && query.length() > 120) {
            throw validation("q", "A busca deve ter no máximo 120 caracteres.");
        }
        return PageEnvelope.from(userRepository.search(parseRole(rawRole), active, query,
                pagination.toPageable(SORT_FIELDS)).map(userMapper::toResponse));
    }

    // A senha temporária fica fora do fingerprint de idempotência, que é um SHA-256 sem sal guardado no banco.
    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse create(UUID actorId, String idempotencyKey, CreateUserRequest request,
            String ipAddress) {
        String email = normalizeEmail(request.email());
        Map<String, Object> fingerprint = Map.of("name", request.name().trim(), "email", email,
                "role", request.role().name());
        return idempotencyService.execute(actorId, "users.create", idempotencyKey, fingerprint, () -> {
            if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
                throw emailAlreadyUsed();
            }
            UserEntity user = new UserEntity();
            user.setName(request.name().trim());
            user.setEmail(email);
            user.setRole(request.role());
            user.setActive(true);
            user.setMustChangePassword(true);
            user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
            user = userRepository.saveAndFlush(user);
            audit(actorId, user, "USER_CREATED", ipAddress);
            return new IdempotentResponse(201, objectMapper.valueToTree(userMapper.toResponse(user)));
        });
    }

    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse update(UUID actorId, UUID userId, String idempotencyKey, UpdateUserRequest request,
            String ipAddress) {
        return idempotencyService.execute(actorId, "users.update." + userId, idempotencyKey, request, () -> {
            if (request.name() == null && request.email() == null && request.role() == null
                    && request.active() == null) {
                throw validation("request", "Informe ao menos um campo para atualizar.");
            }
            boolean mayRemoveOwner = request.role() == Role.STAFF || Boolean.FALSE.equals(request.active());
            // Owners ativos são travados antes do alvo (sempre na mesma ordem) para a contagem valer sob concorrência.
            List<UserEntity> activeOwners = mayRemoveOwner ? userRepository.findActiveByRoleForUpdate(Role.OWNER)
                    : List.of();
            UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> userNotFound(userId));
            boolean self = user.getId().equals(actorId);
            boolean roleChanges = request.role() != null && request.role() != user.getRole();
            boolean deactivates = Boolean.FALSE.equals(request.active()) && user.isActive();

            if (self && deactivates) {
                throw unprocessable("CANNOT_DEACTIVATE_SELF", "Você não pode desativar a própria conta.");
            }
            if (self && roleChanges) {
                throw unprocessable("CANNOT_CHANGE_OWN_ROLE", "Você não pode mudar o próprio papel.");
            }
            if (user.isActive() && user.getRole() == Role.OWNER && (deactivates || roleChanges)
                    && activeOwners.stream().allMatch(owner -> owner.getId().equals(user.getId()))) {
                throw unprocessable("LAST_OWNER", "A loja precisa ter ao menos um owner ativo.");
            }

            if (request.name() != null) {
                if (request.name().isBlank()) {
                    throw validation("name", "O nome não pode ficar vazio.");
                }
                user.setName(request.name().trim());
            }
            if (request.email() != null) {
                String email = normalizeEmail(request.email());
                if (userRepository.findByEmailIgnoreCase(email)
                        .filter(other -> !other.getId().equals(user.getId())).isPresent()) {
                    throw emailAlreadyUsed();
                }
                user.setEmail(email);
            }
            if (roleChanges) {
                user.setRole(request.role());
            }
            if (request.active() != null) {
                user.setActive(request.active());
            }
            if (roleChanges || deactivates) {
                user.setAuthVersion(user.getAuthVersion() + 1);
            }
            UserEntity saved = userRepository.saveAndFlush(user);
            if (roleChanges || deactivates) {
                refreshTokenRepository.revokeAllActiveByUserId(saved.getId(), clock.instant());
            }
            audit(actorId, saved, "USER_UPDATED", ipAddress);
            return new IdempotentResponse(200, objectMapper.valueToTree(userMapper.toResponse(saved)));
        });
    }

    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse resetPassword(UUID actorId, UUID userId, String idempotencyKey,
            ResetPasswordRequest request, String ipAddress) {
        return idempotencyService.execute(actorId, "users.reset-password." + userId, idempotencyKey, Map.of(), () -> {
            UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> userNotFound(userId));
            if (user.getId().equals(actorId)) {
                throw unprocessable("CANNOT_RESET_OWN_PASSWORD",
                        "Para trocar a própria senha, use a troca de senha da sua conta.");
            }
            user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
            user.setMustChangePassword(true);
            user.setAuthVersion(user.getAuthVersion() + 1);
            UserEntity saved = userRepository.saveAndFlush(user);
            refreshTokenRepository.revokeAllActiveByUserId(saved.getId(), clock.instant());
            audit(actorId, saved, "USER_PASSWORD_RESET", ipAddress);
            return new IdempotentResponse(200, objectMapper.valueToTree(userMapper.toResponse(saved)));
        });
    }

    private void audit(UUID actorId, UserEntity user, String action, String ipAddress) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("role", user.getRole().name());
        state.put("active", user.isActive());
        state.put("mustChangePassword", user.isMustChangePassword());
        auditTrail.record(actorId, action, "USER", user.getId(), ipAddress, state);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return Role.fromJson(role.trim());
        } catch (IllegalArgumentException exception) {
            throw validation("role", "role deve ser owner ou staff.");
        }
    }

    private static ApiException emailAlreadyUsed() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED", "Já existe um usuário com este e-mail.");
    }

    private static ApiException userNotFound(UUID userId) {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "O usuário " + userId + " não foi encontrado.");
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of(field, List.of(message)));
    }
}
