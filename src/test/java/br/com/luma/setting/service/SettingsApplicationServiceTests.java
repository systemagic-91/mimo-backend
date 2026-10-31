package br.com.luma.setting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.setting.dto.UpdateSettingsRequest;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.mapper.SettingsMapper;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.repository.AuditLogRepository;

@ExtendWith(MockitoExtension.class)
class SettingsApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Mock
    private SettingsRepository settingsRepository;
    @Mock
    private SettingsMapper settingsMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private IdempotencyService idempotencyService;

    private final SettingsEntity settings = new SettingsEntity();
    private SettingsApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SettingsApplicationService(settingsRepository, settingsMapper, userRepository,
                auditLogRepository, idempotencyService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        settings.setId(UUID.randomUUID());
        settings.setCommissionMin(25);
        settings.setCommissionMax(40);
        settings.setCommissionStep(5);
        settings.setDefaultCommissionRate(30);
        settings.setPaymentTerms(List.of(30, 60, 90));
        settings.setDefaultConsignmentDays(30);
        settings.setTimezone("America/Sao_Paulo");
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(settingsRepository.findSingletonForUpdate()).thenReturn(Optional.of(settings));
    }

    @Test
    void updateStoresSortedTermsTimezoneAndAudit() {
        UserEntity actor = new UserEntity();
        actor.setId(ACTOR_ID);
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        IdempotentResponse response = service.update(ACTOR_ID, "key",
                new UpdateSettingsRequest(35, List.of(90, 30), 45, "America/Manaus"), "203.0.113.10");

        assertThat(response.status()).isEqualTo(200);
        assertThat(settings.getDefaultCommissionRate()).isEqualTo(35);
        assertThat(settings.getPaymentTerms()).containsExactly(30, 90);
        assertThat(settings.getDefaultConsignmentDays()).isEqualTo(45);
        assertThat(settings.getTimezone()).isEqualTo("America/Manaus");
        assertThat(settings.getUpdatedAt()).isEqualTo(NOW);
        assertThat(settings.getUpdatedBy()).isSameAs(actor);
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("SETTINGS_UPDATED");
        assertThat(audit.getValue().getEntityId()).isEqualTo(settings.getId());
    }

    @Test
    void rejectsCommissionOutsideRangeOrStep() {
        assertValidationError(new UpdateSettingsRequest(33, List.of(30), 30, "America/Sao_Paulo"),
                "defaultCommissionRate");
        assertValidationError(new UpdateSettingsRequest(45, List.of(30), 30, "America/Sao_Paulo"),
                "defaultCommissionRate");
    }

    @Test
    void rejectsPaymentTermsOutsideAllowedSetOrRepeated() {
        assertValidationError(new UpdateSettingsRequest(30, List.of(45), 30, "America/Sao_Paulo"), "paymentTerms");
        assertValidationError(new UpdateSettingsRequest(30, List.of(30, 30), 30, "America/Sao_Paulo"),
                "paymentTerms");
        assertValidationError(new UpdateSettingsRequest(30, List.of(), 30, "America/Sao_Paulo"), "paymentTerms");
    }

    @Test
    void rejectsNonPositiveDaysAndUnknownTimezone() {
        assertValidationError(new UpdateSettingsRequest(30, List.of(30), 0, "America/Sao_Paulo"),
                "defaultConsignmentDays");
        assertValidationError(new UpdateSettingsRequest(30, List.of(30), 30, "Mars/Olympus"), "timezone");
    }

    private void assertValidationError(UpdateSettingsRequest request, String field) {
        assertThatThrownBy(() -> service.update(ACTOR_ID, "key", request, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getFields()).containsKey(field);
                });
        verify(auditLogRepository, never()).save(any());
    }
}
