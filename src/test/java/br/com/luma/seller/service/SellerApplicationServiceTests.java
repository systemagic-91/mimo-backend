package br.com.luma.seller.service;

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
import java.time.LocalDate;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.consignment.mapper.ConsignmentMapper;
import br.com.luma.consignment.repository.ConsignmentRepository;
import br.com.luma.seller.dto.CreateSellerRequest;
import br.com.luma.seller.dto.SellerListItemResponse;
import br.com.luma.seller.dto.UpdateSellerRequest;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.seller.mapper.SellerMapper;
import br.com.luma.seller.repository.SellerMetricsRepository;
import br.com.luma.seller.repository.SellerMetricsRepository.SellerOperationalMetrics;
import br.com.luma.seller.repository.SellerMetricsRepository.SoldCommissionMetrics;
import br.com.luma.seller.repository.SellerRepository;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.repository.AuditLogRepository;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.10";

    @Mock
    private SellerRepository sellerRepository;
    @Mock
    private SellerMetricsRepository metricsRepository;
    @Mock
    private SettingsRepository settingsRepository;
    @Mock
    private ConsignmentRepository consignmentRepository;
    @Mock
    private SellerMapper sellerMapper;
    @Mock
    private ConsignmentMapper consignmentMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private IdempotencyService idempotencyService;

    private SellerApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SellerApplicationService(sellerRepository, metricsRepository, settingsRepository,
                consignmentRepository, sellerMapper, consignmentMapper, userRepository, auditLogRepository,
                idempotencyService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(settingsRepository.findFirstBySingletonKeyTrue()).thenReturn(Optional.of(settings()));
        lenient().when(sellerRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SellerEntity seller = invocation.getArgument(0);
            if (seller.getId() == null) {
                seller.setId(UUID.randomUUID());
            }
            return seller;
        });
    }

    @Test
    void createsActiveSellerWithTrimmedDataAndAudit() {
        IdempotentResponse response = service.create(ACTOR_ID, "key",
                new CreateSellerRequest(" Ana Souza ", " 11 99999-0000 ", 30), IP);

        assertThat(response.status()).isEqualTo(201);
        ArgumentCaptor<SellerEntity> saved = ArgumentCaptor.forClass(SellerEntity.class);
        verify(sellerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Ana Souza");
        assertThat(saved.getValue().getPhone()).isEqualTo("11 99999-0000");
        assertThat(saved.getValue().getStatus()).isEqualTo(SellerStatus.ATIVA);
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("SELLER_CREATED");
        assertThat(audit.getValue().getIpAddress()).isEqualTo(IP);
    }

    @Test
    void commissionMustRespectConfiguredRangeAndStep() {
        for (int invalidRate : List.of(20, 33, 45)) {
            assertValidationError(() -> service.create(ACTOR_ID, "key",
                    new CreateSellerRequest("Ana", "11", invalidRate), IP), "commissionRate");
        }
        verify(sellerRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateCanInactivateSellerWithoutTouchingOtherFields() {
        SellerEntity seller = seller(SellerStatus.ATIVA);
        when(sellerRepository.findByIdForUpdate(seller.getId())).thenReturn(Optional.of(seller));

        service.update(ACTOR_ID, seller.getId(), "key", new UpdateSellerRequest(null, null, null, "inativa"), IP);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.INATIVA);
        assertThat(seller.getName()).isEqualTo("Ana");
        assertThat(seller.getCommissionRate()).isEqualTo(30);
    }

    @Test
    void updateRejectsEmptyRequestBlankNameAndUnknownStatus() {
        SellerEntity seller = seller(SellerStatus.ATIVA);
        when(sellerRepository.findByIdForUpdate(seller.getId())).thenReturn(Optional.of(seller));

        assertValidationError(() -> service.update(ACTOR_ID, seller.getId(), "key",
                new UpdateSellerRequest(null, null, null, null), IP), "request");
        assertValidationError(() -> service.update(ACTOR_ID, seller.getId(), "key",
                new UpdateSellerRequest(" ", null, null, null), IP), "name");
        assertValidationError(() -> service.update(ACTOR_ID, seller.getId(), "key",
                new UpdateSellerRequest(null, null, null, "pausada"), IP), "status");
        verify(sellerRepository, never()).saveAndFlush(any());
    }

    @Test
    void updatingUnknownSellerReturnsNotFound() {
        UUID missing = UUID.randomUUID();
        when(sellerRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.update(ACTOR_ID, missing, "key",
                new UpdateSellerRequest("Ana", null, null, null), IP), HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND");
    }

    @Test
    void inactiveSellerCannotReceiveNewConsignments() {
        SellerEntity seller = seller(SellerStatus.INATIVA);
        when(sellerRepository.findByIdAndStatus(seller.getId(), SellerStatus.ATIVA)).thenReturn(Optional.empty());
        when(sellerRepository.findById(seller.getId())).thenReturn(Optional.of(seller));

        assertApiError(() -> service.requireActiveForConsignment(seller.getId()),
                HttpStatus.UNPROCESSABLE_ENTITY, "SELLER_INACTIVE");
    }

    @Test
    void commissionPeriodUsesStoreTimezoneWithExclusiveEnd() {
        SellerEntity seller = seller(SellerStatus.ATIVA);
        when(sellerRepository.findById(seller.getId())).thenReturn(Optional.of(seller));
        Instant from = Instant.parse("2026-10-01T03:00:00Z");
        Instant toExclusive = Instant.parse("2026-11-01T03:00:00Z");
        when(metricsRepository.findCommission(seller.getId(), from, toExclusive))
                .thenReturn(new SoldCommissionMetrics(2, 20_000, 6_000));

        var response = service.commission(seller.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(response.soldCount()).isEqualTo(2);
        assertThat(response.commissionCents()).isEqualTo(6_000);
    }

    @Test
    void commissionRejectsInvertedPeriod() {
        SellerEntity seller = seller(SellerStatus.ATIVA);
        when(sellerRepository.findById(seller.getId())).thenReturn(Optional.of(seller));

        assertValidationError(() -> service.commission(seller.getId(),
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)), "period");
    }

    @Test
    void listFlagsLateSellersAndCountsDueDaysInStoreTimezone() {
        SellerEntity late = seller(SellerStatus.ATIVA);
        SellerEntity idle = seller(SellerStatus.ATIVA);
        when(sellerRepository.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of(late, idle)));
        when(metricsRepository.findForSellers(List.of(late.getId(), idle.getId()))).thenReturn(Map.of(
                late.getId(), new SellerOperationalMetrics(late.getId(), 3, 30_000,
                        Instant.parse("2026-10-29T15:00:00Z"), 1, 0, 0, 0)));

        var items = service.list(null, null, new PaginationRequest()).items();

        SellerListItemResponse lateItem = items.get(0);
        assertThat(lateItem.late()).isTrue();
        assertThat(lateItem.nextDueDays()).isEqualTo(-1);
        assertThat(lateItem.piecesInHand()).isEqualTo(3);
        SellerListItemResponse idleItem = items.get(1);
        assertThat(idleItem.late()).isFalse();
        assertThat(idleItem.nextDueDays()).isNull();
        assertThat(idleItem.piecesInHand()).isZero();
    }

    private SellerEntity seller(SellerStatus status) {
        SellerEntity seller = new SellerEntity();
        seller.setId(UUID.randomUUID());
        seller.setName("Ana");
        seller.setPhone("11");
        seller.setCommissionRate(30);
        seller.setStatus(status);
        return seller;
    }

    private static SettingsEntity settings() {
        SettingsEntity settings = new SettingsEntity();
        settings.setId(UUID.randomUUID());
        settings.setCommissionMin(25);
        settings.setCommissionMax(40);
        settings.setCommissionStep(5);
        settings.setDefaultCommissionRate(30);
        settings.setPaymentTerms(List.of(30, 60, 90));
        settings.setDefaultConsignmentDays(30);
        settings.setTimezone("America/Sao_Paulo");
        return settings;
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(exception.getFields()).containsKey(field);
        });
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
