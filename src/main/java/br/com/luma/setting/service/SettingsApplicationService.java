package br.com.luma.setting.service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.setting.dto.UpdateSettingsRequest;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.mapper.SettingsMapper;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.dto.SettingsResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.repository.AuditLogRepository;
import br.com.luma.shared.entity.AuditLogEntity;

@Service
public class SettingsApplicationService {

    private static final Set<Integer> ALLOWED_PAYMENT_TERMS = Set.of(30, 60, 90);

    private final SettingsRepository settingsRepository;
    private final SettingsMapper settingsMapper;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SettingsApplicationService(SettingsRepository settingsRepository, SettingsMapper settingsMapper,
            UserRepository userRepository, AuditLogRepository auditLogRepository,
            IdempotencyService idempotencyService, ObjectMapper objectMapper, Clock clock) {
        this.settingsRepository = settingsRepository;
        this.settingsMapper = settingsMapper;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public SettingsResponse get() {
        return settingsMapper.toResponse(getSettings());
    }

    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse update(UUID actorId, String idempotencyKey,
            UpdateSettingsRequest request, String ipAddress) {
        return idempotencyService.execute(actorId, "settings.update", idempotencyKey, request, () -> {
            SettingsEntity settings = settingsRepository.findSingletonForUpdate()
                    .orElseThrow(() -> new IllegalStateException("Default store settings are missing"));
            validate(request, settings);
            settings.setDefaultCommissionRate(request.defaultCommissionRate());
            settings.setPaymentTerms(request.paymentTerms().stream().distinct().sorted().toList());
            settings.setDefaultConsignmentDays(request.defaultConsignmentDays());
            settings.setTimezone(request.timezone().trim());
            settings.setUpdatedAt(clock.instant());
            settings.setUpdatedBy(userRepository.getReferenceById(actorId));
            SettingsResponse response = settingsMapper.toResponse(settings);

            AuditLogEntity audit = new AuditLogEntity();
            audit.setActor(userRepository.getReferenceById(actorId));
            audit.setAction("SETTINGS_UPDATED");
            audit.setEntityType("SETTINGS");
            audit.setEntityId(settings.getId());
            audit.setAfterState(Map.of(
                    "defaultCommissionRate", settings.getDefaultCommissionRate(),
                    "defaultConsignmentDays", settings.getDefaultConsignmentDays(),
                    "timezone", settings.getTimezone()));
            audit.setIpAddress(ipAddress);
            audit.setOccurredAt(clock.instant());
            auditLogRepository.save(audit);
            return new IdempotentResponse(200, objectMapper.valueToTree(response));
        });
    }

    private SettingsEntity getSettings() {
        return settingsRepository.findFirstBySingletonKeyTrue()
                .orElseThrow(() -> new IllegalStateException("Default store settings are missing"));
    }

    private void validate(UpdateSettingsRequest request, SettingsEntity settings) {
        int rate = request.defaultCommissionRate();
        if (rate < settings.getCommissionMin() || rate > settings.getCommissionMax()
                || (rate - settings.getCommissionMin()) % settings.getCommissionStep() != 0) {
            throw validation("defaultCommissionRate", "A comissão deve respeitar a faixa e o passo configurados.");
        }
        if (request.paymentTerms() == null || request.paymentTerms().isEmpty()
                || request.paymentTerms().size() > ALLOWED_PAYMENT_TERMS.size()
                || new HashSet<>(request.paymentTerms()).size() != request.paymentTerms().size()
                || !ALLOWED_PAYMENT_TERMS.containsAll(request.paymentTerms())) {
            throw validation("paymentTerms", "Use uma lista sem repetição com pelo menos um prazo entre 30, 60 e 90 dias.");
        }
        if (request.defaultConsignmentDays() == null || request.defaultConsignmentDays() < 1) {
            throw validation("defaultConsignmentDays", "O prazo padrão deve ser maior ou igual a 1 dia.");
        }
        try {
            ZoneId.of(request.timezone());
        } catch (RuntimeException exception) {
            throw validation("timezone", "Informe um timezone IANA válido.");
        }
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message,
                Map.of(field, List.of(message)));
    }
}