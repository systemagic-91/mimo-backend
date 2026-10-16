package br.com.luma.seller.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.consignment.mapper.ConsignmentMapper;
import br.com.luma.consignment.repository.ConsignmentRepository;
import br.com.luma.seller.dto.CreateSellerRequest;
import br.com.luma.seller.dto.SellerCommissionResponse;
import br.com.luma.seller.dto.SellerListItemResponse;
import br.com.luma.seller.dto.SellerStatsResponse;
import br.com.luma.seller.dto.UpdateSellerRequest;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.seller.mapper.SellerMapper;
import br.com.luma.seller.repository.SellerMetricsRepository;
import br.com.luma.seller.repository.SellerMetricsRepository.SellerOperationalMetrics;
import br.com.luma.seller.repository.SellerRepository;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.dto.ConsignmentResponse;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.SellerResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.repository.AuditLogRepository;
import br.com.luma.shared.entity.AuditLogEntity;

@Service
public class SellerApplicationService {

    private static final Set<String> SELLER_SORT_FIELDS = Set.of(
            "name", "phone", "commissionRate", "status", "createdAt", "updatedAt");
    private static final Set<String> CONSIGNMENT_SORT_FIELDS = Set.of(
            "code", "deliveredAt", "dueAt", "status", "createdAt");

    private final SellerRepository sellerRepository;
    private final SellerMetricsRepository metricsRepository;
    private final SettingsRepository settingsRepository;
    private final ConsignmentRepository consignmentRepository;
    private final SellerMapper sellerMapper;
    private final ConsignmentMapper consignmentMapper;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SellerApplicationService(SellerRepository sellerRepository,
            SellerMetricsRepository metricsRepository,
            SettingsRepository settingsRepository,
            ConsignmentRepository consignmentRepository,
            SellerMapper sellerMapper,
            ConsignmentMapper consignmentMapper,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.sellerRepository = sellerRepository;
        this.metricsRepository = metricsRepository;
        this.settingsRepository = settingsRepository;
        this.consignmentRepository = consignmentRepository;
        this.sellerMapper = sellerMapper;
        this.consignmentMapper = consignmentMapper;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public PageEnvelope<SellerListItemResponse> list(String rawStatus, String rawQuery,
            PaginationRequest pagination) {
        SellerStatus status = parseStatus(rawStatus);
        String query = rawQuery == null || rawQuery.isBlank() ? null : rawQuery.trim();
        if (query != null && query.length() > 120) {
            throw validation("q", "A busca deve ter no máximo 120 caracteres.");
        }
        Pageable pageable = pagination.toPageable(SELLER_SORT_FIELDS);
        Page<SellerEntity> sellers = sellerRepository.search(status, query, pageable);
        List<UUID> sellerIds = sellers.getContent().stream().map(SellerEntity::getId).toList();
        Map<UUID, SellerOperationalMetrics> metrics = metricsRepository.findForSellers(sellerIds);
        ZoneId zone = ZoneId.of(getSettings().getTimezone());
        Instant now = clock.instant();

        Page<SellerListItemResponse> responsePage = sellers.map(seller -> {
            SellerOperationalMetrics summary = metrics.getOrDefault(seller.getId(),
                    SellerOperationalMetrics.empty(seller.getId()));
            Integer nextDueDays = summary.nextDueAt() == null ? null
                    : Math.toIntExact(ChronoUnit.DAYS.between(
                            now.atZone(zone).toLocalDate(), summary.nextDueAt().atZone(zone).toLocalDate()));
            return new SellerListItemResponse(sellerMapper.toResponse(seller), summary.piecesInHand(),
                    summary.valueInHandCents(), summary.nextDueAt(), nextDueDays,
                    summary.nextDueAt() != null && summary.nextDueAt().isBefore(now), summary.openLots());
        });
        return PageEnvelope.from(responsePage);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public SellerResponse get(UUID sellerId) {
        return sellerMapper.toResponse(findSeller(sellerId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public SellerEntity requireActiveForConsignment(UUID sellerId) {
        return sellerRepository.findByIdAndStatus(sellerId, SellerStatus.ATIVA)
                .orElseGet(() -> {
                    SellerEntity seller = findSeller(sellerId);
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELLER_INACTIVE",
                            "Vendedoras inativas não podem receber novas consignações.");
                });
    }

    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse create(UUID actorId, String idempotencyKey, CreateSellerRequest request,
            String ipAddress) {
        return idempotencyService.execute(actorId, "sellers.create", idempotencyKey, request, () -> {
            SettingsEntity settings = getSettings();
            validateCommission(request.commissionRate(), settings);

            SellerEntity seller = new SellerEntity();
            seller.setName(request.name().trim());
            seller.setPhone(request.phone().trim());
            seller.setCommissionRate(request.commissionRate());
            seller.setStatus(SellerStatus.ATIVA);
            seller = sellerRepository.saveAndFlush(seller);
            audit(actorId, seller.getId(), "SELLER_CREATED", ipAddress,
                    Map.of("status", seller.getStatus().name(), "commissionRate", seller.getCommissionRate()));
            return new IdempotentResponse(201, objectMapper.valueToTree(sellerMapper.toResponse(seller)));
        });
    }

    @Transactional
    @PreAuthorize("hasRole('OWNER')")
    public IdempotentResponse update(UUID actorId, UUID sellerId, String idempotencyKey,
            UpdateSellerRequest request, String ipAddress) {
        return idempotencyService.execute(actorId, "sellers.update." + sellerId, idempotencyKey, request, () -> {
            if (request.name() == null && request.phone() == null && request.commissionRate() == null
                    && request.status() == null) {
                throw validation("request", "Informe ao menos um campo para atualizar.");
            }
            SellerEntity seller = sellerRepository.findByIdForUpdate(sellerId)
                    .orElseThrow(() -> sellerNotFound(sellerId));
            if (request.name() != null) {
                if (request.name().isBlank()) {
                    throw validation("name", "O nome não pode ficar vazio.");
                }
                seller.setName(request.name().trim());
            }
            if (request.phone() != null) {
                seller.setPhone(request.phone().trim());
            }
            if (request.commissionRate() != null) {
                validateCommission(request.commissionRate(), getSettings());
                seller.setCommissionRate(request.commissionRate());
            }
            if (request.status() != null) {
                seller.setStatus(parseRequiredStatus(request.status()));
            }
            seller = sellerRepository.saveAndFlush(seller);
            audit(actorId, sellerId, "SELLER_UPDATED", ipAddress,
                    Map.of("status", seller.getStatus().name(), "commissionRate", seller.getCommissionRate()));
            return new IdempotentResponse(200, objectMapper.valueToTree(sellerMapper.toResponse(seller)));
        });
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public SellerStatsResponse stats(UUID sellerId) {
        findSeller(sellerId);
        SellerOperationalMetrics metrics = metricsRepository.findForSeller(sellerId);
        return new SellerStatsResponse(metrics.piecesInHand(), metrics.valueInHandCents(),
                metrics.totalSoldCount(), metrics.totalSoldValueCents(), metrics.totalCommissionCents());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public SellerCommissionResponse commission(UUID sellerId, LocalDate from, LocalDate to) {
        findSeller(sellerId);
        if (from == null || to == null || from.isAfter(to)) {
            throw validation("period", "Informe um período válido, com from menor ou igual a to.");
        }
        ZoneId zone = ZoneId.of(getSettings().getTimezone());
        Instant fromInclusive = from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant();
        var metrics = metricsRepository.findCommission(sellerId, fromInclusive, toExclusive);
        return new SellerCommissionResponse(metrics.soldCount(), metrics.soldValueCents(), metrics.commissionCents());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public PageEnvelope<ConsignmentResponse> consignments(UUID sellerId, String rawStatus,
            PaginationRequest pagination) {
        findSeller(sellerId);
        Pageable pageable = pagination.toPageable(CONSIGNMENT_SORT_FIELDS);
        br.com.luma.shared.ConsignmentStatus status = parseConsignmentStatus(rawStatus);
        Page<br.com.luma.consignment.entity.ConsignmentEntity> consignments = status == null
                ? consignmentRepository.findAllBySeller_IdOrderByDeliveredAtDesc(sellerId, pageable)
                : consignmentRepository.findAllBySeller_IdAndStatusOrderByDeliveredAtDesc(sellerId, status, pageable);
        return PageEnvelope.from(consignments.map(consignmentMapper::toResponse));
    }

    private SellerEntity findSeller(UUID sellerId) {
        return sellerRepository.findById(sellerId).orElseThrow(() -> sellerNotFound(sellerId));
    }

    private SettingsEntity getSettings() {
        return settingsRepository.findFirstBySingletonKeyTrue()
                .orElseThrow(() -> new IllegalStateException("Default store settings are missing"));
    }

    private void validateCommission(int rate, SettingsEntity settings) {
        if (rate < settings.getCommissionMin() || rate > settings.getCommissionMax()
                || (rate - settings.getCommissionMin()) % settings.getCommissionStep() != 0) {
            throw validation("commissionRate", "A comissão deve respeitar a faixa e o passo configurados.");
        }
    }

    private void audit(UUID actorId, UUID sellerId, String action, String ipAddress, Map<String, Object> state) {
        AuditLogEntity event = new AuditLogEntity();
        event.setActor(userRepository.getReferenceById(actorId));
        event.setAction(action);
        event.setEntityType("SELLER");
        event.setEntityId(sellerId);
        event.setAfterState(state);
        event.setIpAddress(ipAddress);
        event.setOccurredAt(clock.instant());
        auditLogRepository.save(event);
    }

    private static SellerStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return parseRequiredStatus(status);
    }

    private static SellerStatus parseRequiredStatus(String status) {
        try {
            return SellerStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw validation("status", "status deve ser ativa ou inativa.");
        }
    }

    private static br.com.luma.shared.ConsignmentStatus parseConsignmentStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return br.com.luma.shared.ConsignmentStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw validation("status", "status deve ser aberta ou quitada.");
        }
    }

    private static ApiException sellerNotFound(UUID sellerId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND",
                "A vendedora " + sellerId + " não foi encontrada.");
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of(field, List.of(message)));
    }
}