package br.com.luma.consignment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.consignment.dto.ConsignmentDetailsResponse;
import br.com.luma.consignment.dto.ConsignmentListItemResponse;
import br.com.luma.consignment.dto.CreateConsignmentRequest;
import br.com.luma.consignment.dto.MarkItemReturnedRequest;
import br.com.luma.consignment.dto.MarkItemSoldRequest;
import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.consignment.mapper.ConsignmentItemMapper;
import br.com.luma.consignment.repository.ConsignmentItemRepository;
import br.com.luma.consignment.repository.ConsignmentItemRepository.InHandSummary;
import br.com.luma.consignment.repository.ConsignmentRepository;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.piece.repository.PieceEventRepository;
import br.com.luma.piece.repository.PieceRepository;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.seller.mapper.SellerMapper;
import br.com.luma.seller.repository.SellerRepository;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.ConsignmentItemStatus;
import br.com.luma.shared.CalendarDays;
import br.com.luma.shared.ConsignmentStatus;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.metrics.BusinessMetrics;
import br.com.luma.shared.security.CurrentUserRoles;
import br.com.luma.shared.dto.ConsignmentItemResponse;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.shared.repository.AuditLogRepository;

@Service
public class ConsignmentApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsignmentApplicationService.class);
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "code", "deliveredAt", "dueAt", "status", "createdAt");
    private static final int MAX_DUE_DAYS = 365;
    private static final int LOCATION_MAX_LENGTH = 120;
    private static final int OVERDUE_ANY = 0;
    private static final int OVERDUE_ONLY = 1;
    private static final int OVERDUE_NONE = 2;

    private final ConsignmentRepository consignmentRepository;
    private final ConsignmentItemRepository itemRepository;
    private final SellerRepository sellerRepository;
    private final PieceRepository pieceRepository;
    private final PieceEventRepository pieceEventRepository;
    private final SettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ConsignmentItemMapper itemMapper;
    private final SellerMapper sellerMapper;
    private final IdempotencyService idempotencyService;
    private final BusinessMetrics metrics;
    private final CurrentUserRoles currentUserRoles;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConsignmentApplicationService(ConsignmentRepository consignmentRepository,
            ConsignmentItemRepository itemRepository, SellerRepository sellerRepository,
            PieceRepository pieceRepository, PieceEventRepository pieceEventRepository,
            SettingsRepository settingsRepository, UserRepository userRepository,
            AuditLogRepository auditLogRepository, ConsignmentItemMapper itemMapper, SellerMapper sellerMapper,
            IdempotencyService idempotencyService, BusinessMetrics metrics, CurrentUserRoles currentUserRoles,
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.consignmentRepository = consignmentRepository;
        this.itemRepository = itemRepository;
        this.sellerRepository = sellerRepository;
        this.pieceRepository = pieceRepository;
        this.pieceEventRepository = pieceEventRepository;
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.itemMapper = itemMapper;
        this.sellerMapper = sellerMapper;
        this.idempotencyService = idempotencyService;
        this.metrics = metrics;
        this.currentUserRoles = currentUserRoles;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageEnvelope<ConsignmentListItemResponse> list(String rawStatus, UUID sellerId, Boolean overdue,
            PaginationRequest pagination) {
        ConsignmentStatus status = parseStatus(rawStatus);
        ZoneId zone = storeZone();
        Instant now = clock.instant();
        Pageable pageable = pagination.toPageable(ALLOWED_SORT_FIELDS);
        if (pageable.getSort().isUnsorted()) {
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "deliveredAt"));
        }
        int overdueFilter = overdue == null ? OVERDUE_ANY : overdue ? OVERDUE_ONLY : OVERDUE_NONE;
        Page<ConsignmentEntity> page = consignmentRepository.search(status, sellerId, overdueFilter, now, pageable);

        List<UUID> ids = page.getContent().stream().map(ConsignmentEntity::getId).toList();
        Map<UUID, InHandSummary> inHand = ids.isEmpty() ? Map.of()
                : itemRepository.summarizeInHand(ids).stream()
                        .collect(Collectors.toMap(InHandSummary::getConsignmentId, Function.identity()));

        return PageEnvelope.from(page.map(consignment -> {
            InHandSummary summary = inHand.get(consignment.getId());
            return new ConsignmentListItemResponse(consignment.getId(), consignment.getCode(),
                    consignment.getSeller().getId(), consignment.getDeliveredAt(), consignment.getDueAt(),
                    CalendarDays.between(now, consignment.getDueAt(), zone), visualStatus(consignment, now),
                    consignment.getPaymentTermDays(), consignment.getNote(), consignment.getCreatedBy().getId(),
                    consignment.getVersion(), sellerMapper.toResponse(consignment.getSeller()),
                    summary == null ? 0 : summary.getPiecesInHand(),
                    summary == null ? 0 : summary.getValueInHandCents());
        }));
    }

    @Transactional(readOnly = true)
    public ConsignmentDetailsResponse get(UUID consignmentId) {
        ConsignmentEntity consignment = consignmentRepository.findById(consignmentId)
                .orElseThrow(() -> consignmentNotFound(consignmentId));
        return details(consignment);
    }

    @Transactional
    public IdempotentResponse create(UUID actorId, String idempotencyKey, CreateConsignmentRequest request,
            String ipAddress) {
        return idempotencyService.execute(actorId, "consignments.create", idempotencyKey, request, () -> {
            List<UUID> pieceIds = request.pieceIds();
            if (pieceIds.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_ITEMS",
                        "Selecione ao menos uma peça para o lote.");
            }
            if (new HashSet<>(pieceIds).size() != pieceIds.size()) {
                throw validation("pieceIds", "A mesma peça foi enviada mais de uma vez.");
            }

            SettingsEntity settings = settings();
            ZoneId zone = ZoneId.of(settings.getTimezone());
            Instant now = clock.instant();
            LocalDate today = now.atZone(zone).toLocalDate();
            if (!settings.getPaymentTerms().contains(request.paymentTermDays())) {
                throw validation("paymentTermDays", "O prazo de pagamento deve ser um dos prazos permitidos: "
                        + settings.getPaymentTerms() + ".");
            }
            if (request.dueDate().isBefore(today)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUE_DATE_IN_PAST",
                        "O vencimento não pode ser anterior a hoje.", Map.of("dueDate",
                                List.of("O vencimento não pode ser anterior a hoje.")));
            }
            if (request.dueDate().isAfter(today.plusDays(MAX_DUE_DAYS))) {
                throw validation("dueDate", "O vencimento deve ser em até " + MAX_DUE_DAYS + " dias.");
            }

            SellerEntity seller = sellerRepository.findByIdForUpdate(request.sellerId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND",
                            "A vendedora " + request.sellerId() + " não foi encontrada."));
            if (seller.getStatus() != SellerStatus.ATIVA) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELLER_INACTIVE",
                        "Vendedoras inativas não podem receber novas consignações.");
            }

            List<PieceEntity> pieces = pieceRepository.findAllForUpdate(pieceIds.stream().sorted().toList());
            requireAllFound(pieceIds, pieces);
            requireAvailable(pieces);

            String code = allocateConsignmentCode(today.getYear());
            ConsignmentEntity consignment = new ConsignmentEntity();
            consignment.setCode(code);
            consignment.setSeller(seller);
            consignment.setDeliveredAt(now);
            consignment.setDueAt(endOfDay(request.dueDate(), zone));
            consignment.setStatus(ConsignmentStatus.ABERTA);
            consignment.setNote(normalizeOptional(request.note()));
            consignment.setPaymentTermDays(request.paymentTermDays());
            consignment.setCreatedBy(userRepository.getReferenceById(actorId));
            consignment = consignmentRepository.save(consignment);

            List<ConsignmentItemEntity> items = new ArrayList<>();
            List<PieceEventEntity> events = new ArrayList<>();
            String location = truncate("Com: " + seller.getName(), LOCATION_MAX_LENGTH);
            for (PieceEntity piece : pieces) {
                ConsignmentItemEntity item = new ConsignmentItemEntity();
                item.setConsignment(consignment);
                item.setPiece(piece);
                item.setCodeSnapshot(piece.getCode());
                item.setTypeSnapshot(piece.getType());
                item.setValueCentsSnapshot(piece.getValueCents());
                item.setCommissionRateSnapshot(seller.getCommissionRate());
                item.setStatus(ConsignmentItemStatus.COM_VENDEDORA);
                items.add(item);

                piece.setStatus(PieceStage.CONSIGNACAO);
                piece.setCurrentLocation(location);
                events.add(createEvent(piece, PieceStage.CONSIGNACAO, nextEventTime(piece.getId(), now),
                        "Lote " + code + " com " + seller.getName(), actorId));
            }
            itemRepository.saveAll(items);
            pieceRepository.saveAll(pieces);
            pieceEventRepository.saveAll(events);
            consignment.getItems().addAll(items);

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("code", code);
            state.put("sellerId", seller.getId().toString());
            state.put("pieceIds", pieces.stream().map(piece -> piece.getId().toString()).toList());
            state.put("dueAt", consignment.getDueAt().toString());
            state.put("paymentTermDays", consignment.getPaymentTermDays());
            audit(actorId, consignment.getId(), "CONSIGNMENT_CREATED", ipAddress, state);
            metrics.consignmentCreated(items.size());

            consignmentRepository.flush();
            return new IdempotentResponse(201, objectMapper.valueToTree(details(consignment, items, now, zone)));
        });
    }

    @Transactional
    public IdempotentResponse markSold(UUID actorId, String idempotencyKey, UUID consignmentId, UUID pieceId,
            MarkItemSoldRequest request, String ipAddress) {
        Instant requestedAt = request == null ? null : request.soldAt();
        String note = request == null ? null : normalizeOptional(request.note());
        return idempotencyService.execute(actorId, "consignments.items.sold", idempotencyKey,
                new ItemActionFingerprint(consignmentId, pieceId, requestedAt, note), () -> {
                    ItemAction action = lockItem(consignmentId, pieceId);
                    Instant soldAt = resolveActionTime(requestedAt, action.consignment(), "soldAt");

                    action.item().setStatus(ConsignmentItemStatus.VENDIDA);
                    action.item().setSoldAt(soldAt);
                    action.piece().setStatus(PieceStage.VENDIDA);
                    action.piece().setCurrentLocation("Vendida");
                    pieceEventRepository.save(createEvent(action.piece(), PieceStage.VENDIDA,
                            nextEventTime(pieceId, soldAt),
                            note != null ? note : "Vendida por " + action.consignment().getSeller().getName(),
                            actorId));
                    metrics.consignmentItemSold();
                    return finishItemAction(actorId, action, "CONSIGNMENT_ITEM_SOLD", ipAddress);
                });
    }

    @Transactional
    public IdempotentResponse markReturned(UUID actorId, String idempotencyKey, UUID consignmentId, UUID pieceId,
            MarkItemReturnedRequest request, String ipAddress) {
        Instant requestedAt = request == null ? null : request.returnedAt();
        String note = request == null ? null : normalizeOptional(request.note());
        return idempotencyService.execute(actorId, "consignments.items.returned", idempotencyKey,
                new ItemActionFingerprint(consignmentId, pieceId, requestedAt, note), () -> {
                    ItemAction action = lockItem(consignmentId, pieceId);
                    Instant returnedAt = resolveActionTime(requestedAt, action.consignment(), "returnedAt");

                    action.item().setStatus(ConsignmentItemStatus.DEVOLVIDA);
                    action.item().setReturnedAt(returnedAt);
                    action.piece().setStatus(PieceStage.ETIQUETADA);
                    action.piece().setCurrentLocation("Estoque");
                    Instant returnedEventAt = nextEventTime(pieceId, returnedAt);
                    pieceEventRepository.saveAll(List.of(
                            createEvent(action.piece(), PieceStage.DEVOLVIDA, returnedEventAt,
                                    note != null ? note
                                            : "Devolvida por " + action.consignment().getSeller().getName(),
                                    actorId),
                            createEvent(action.piece(), PieceStage.ETIQUETADA, returnedEventAt.plusMillis(1),
                                    "De volta ao estoque", actorId)));
                    metrics.consignmentItemReturned();
                    return finishItemAction(actorId, action, "CONSIGNMENT_ITEM_RETURNED", ipAddress);
                });
    }

    private ItemAction lockItem(UUID consignmentId, UUID pieceId) {
        ConsignmentEntity consignment = consignmentRepository.findByIdForUpdate(consignmentId)
                .orElseThrow(() -> consignmentNotFound(consignmentId));
        ConsignmentItemEntity item = itemRepository.findForUpdate(consignmentId, pieceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONSIGNMENT_ITEM_NOT_FOUND",
                        "A peça " + pieceId + " não faz parte do lote " + consignment.getCode() + "."));
        if (item.getStatus() != ConsignmentItemStatus.COM_VENDEDORA) {
            String state = item.getStatus() == ConsignmentItemStatus.VENDIDA ? "vendida" : "devolvida";
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_NOT_WITH_SELLER",
                    "A peça " + item.getCodeSnapshot() + " já foi marcada como " + state + ".");
        }
        PieceEntity piece = pieceRepository.findByIdForUpdate(pieceId)
                .orElseThrow(() -> new IllegalStateException("Consignment item references a missing piece"));
        if (piece.getStatus() != PieceStage.CONSIGNACAO) {
            LOGGER.warn("Inconsistent consignment item consignmentId={} pieceId={} pieceStatus={}",
                    consignmentId, pieceId, piece.getStatus());
            throw inconsistent(List.of(piece));
        }
        return new ItemAction(consignment, item, piece);
    }

    private IdempotentResponse finishItemAction(UUID actorId, ItemAction action, String auditAction,
            String ipAddress) {
        ConsignmentEntity consignment = action.consignment();
        itemRepository.save(action.item());
        pieceRepository.save(action.piece());
        long remaining = itemRepository.countByConsignment_IdAndStatus(consignment.getId(),
                ConsignmentItemStatus.COM_VENDEDORA);
        boolean settled = remaining == 0;
        if (settled) {
            consignment.setStatus(ConsignmentStatus.QUITADA);
            consignmentRepository.save(consignment);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("pieceId", action.piece().getId().toString());
        state.put("itemStatus", action.item().getStatus().name());
        state.put("consignmentStatus", consignment.getStatus().name());
        audit(actorId, consignment.getId(), auditAction, ipAddress, state);

        consignmentRepository.flush();
        return new IdempotentResponse(200, objectMapper.valueToTree(details(consignment)));
    }

    private void requireAllFound(List<UUID> requestedIds, List<PieceEntity> pieces) {
        if (pieces.size() == requestedIds.size()) {
            return;
        }
        Set<UUID> found = pieces.stream().map(PieceEntity::getId).collect(Collectors.toSet());
        List<String> missing = requestedIds.stream().filter(id -> !found.contains(id)).map(UUID::toString)
                .toList();
        throw new ApiException(HttpStatus.NOT_FOUND, "PIECE_NOT_FOUND",
                "Algumas peças não foram encontradas.", Map.of("pieceIds", missing));
    }

    private void requireAvailable(List<PieceEntity> pieces) {
        List<PieceEntity> unavailable = pieces.stream()
                .filter(piece -> piece.getStatus() != PieceStage.ETIQUETADA)
                .toList();
        if (!unavailable.isEmpty()) {
            String codes = unavailable.stream().map(PieceEntity::getCode).collect(Collectors.joining(", "));
            throw new ApiException(HttpStatus.CONFLICT, "PIECE_NOT_AVAILABLE",
                    "Estas peças não estão disponíveis no estoque: " + codes + ".",
                    Map.of("pieceIds", unavailable.stream().map(piece -> piece.getId().toString()).toList()));
        }
        List<UUID> ids = pieces.stream().map(PieceEntity::getId).toList();
        List<ConsignmentItemEntity> active = itemRepository.findAllByPiece_IdInAndStatus(ids,
                ConsignmentItemStatus.COM_VENDEDORA);
        if (!active.isEmpty()) {
            Set<UUID> activeIds = active.stream().map(item -> item.getPiece().getId()).collect(Collectors.toSet());
            List<PieceEntity> conflicting = pieces.stream().filter(piece -> activeIds.contains(piece.getId()))
                    .toList();
            LOGGER.warn("Pieces labeled in stock but still active in a consignment pieceIds={}", activeIds);
            throw inconsistent(conflicting);
        }
    }

    private ConsignmentDetailsResponse details(ConsignmentEntity consignment) {
        return details(consignment, itemRepository.findAllByConsignment_IdOrderByCodeSnapshotAsc(consignment.getId()),
                clock.instant(), storeZone());
    }

    private ConsignmentDetailsResponse details(ConsignmentEntity consignment, List<ConsignmentItemEntity> items,
            Instant now, ZoneId zone) {
        boolean showAmounts = currentUserRoles.isOwner();
        List<ConsignmentItemEntity> sorted = items.stream()
                .sorted((left, right) -> left.getCodeSnapshot().compareTo(right.getCodeSnapshot()))
                .toList();
        return new ConsignmentDetailsResponse(consignment.getId(), consignment.getCode(),
                consignment.getSeller().getId(), consignment.getDeliveredAt(), consignment.getDueAt(),
                consignment.getStatus(), isLate(consignment, now), consignment.getPaymentTermDays(),
                consignment.getNote(),
                consignment.getCreatedBy().getId(), consignment.getVersion(),
                sellerMapper.toResponse(consignment.getSeller()),
                sorted.stream().map(item -> withPayment(item, consignment.getPaymentTermDays(), zone, showAmounts))
                        .toList());
    }

    private ConsignmentItemResponse withPayment(ConsignmentItemEntity item, Integer paymentTermDays, ZoneId zone,
            boolean showAmounts) {
        ConsignmentItemResponse response = itemMapper.toResponse(item);
        if (paymentTermDays == null || item.getStatus() != ConsignmentItemStatus.VENDIDA || item.getSoldAt() == null) {
            return response;
        }
        return response.withPayment(PaymentSchedule.dueDate(item.getSoldAt(), paymentTermDays, zone),
                showAmounts ? PaymentSchedule.amountDue(item.getValueCentsSnapshot(), item.getCommissionRateSnapshot())
                        : null);
    }

    private Instant resolveActionTime(Instant requested, ConsignmentEntity consignment, String field) {
        Instant now = clock.instant();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now)) {
            throw validation(field, "A data não pode estar no futuro.");
        }
        if (requested.isBefore(consignment.getDeliveredAt())) {
            throw validation(field, "A data não pode ser anterior à entrega do lote.");
        }
        return requested;
    }

    private Instant nextEventTime(UUID pieceId, Instant desired) {
        return pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(pieceId)
                .map(event -> {
                    Instant earliestNext = event.getOccurredAt().plusMillis(1);
                    return desired.isAfter(earliestNext) ? desired : earliestNext;
                })
                .orElse(desired);
    }

    private PieceEventEntity createEvent(PieceEntity piece, PieceStage stage, Instant occurredAt, String note,
            UUID actorId) {
        PieceEventEntity event = new PieceEventEntity();
        event.setPiece(piece);
        event.setStage(stage);
        event.setOccurredAt(occurredAt);
        event.setNote(truncate(note, 500));
        event.setActor(userRepository.getReferenceById(actorId));
        return event;
    }

    private String allocateConsignmentCode(int year) {
        Long sequence = jdbcTemplate.queryForObject(
                "insert into code_sequences (id, scope, sequence_year, next_value) "
                        + "values (?, 'CONSIGNMENT', ?, 2) "
                        + "on conflict (scope, sequence_year) do update "
                        + "set next_value = code_sequences.next_value + 1 "
                        + "returning next_value - 1",
                Long.class, UUID.randomUUID(), year);
        if (sequence == null || sequence < 1) {
            throw new IllegalStateException("Consignment code sequence returned an invalid value");
        }
        return String.format(Locale.ROOT, "C-%d-%03d", year, sequence);
    }

    private void audit(UUID actorId, UUID consignmentId, String action, String ipAddress,
            Map<String, Object> state) {
        AuditLogEntity event = new AuditLogEntity();
        event.setActor(userRepository.getReferenceById(actorId));
        event.setAction(action);
        event.setEntityType("CONSIGNMENT");
        event.setEntityId(consignmentId);
        event.setAfterState(state);
        event.setIpAddress(ipAddress);
        event.setOccurredAt(clock.instant());
        auditLogRepository.save(event);
    }

    private ZoneId storeZone() {
        return ZoneId.of(settings().getTimezone());
    }

    private SettingsEntity settings() {
        return settingsRepository.findFirstBySingletonKeyTrue()
                .orElseThrow(() -> new IllegalStateException("Store settings are missing"));
    }

    static Instant endOfDay(LocalDate date, ZoneId zone) {
        return date.atTime(LocalTime.MAX).atZone(zone).toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isLate(ConsignmentEntity consignment, Instant now) {
        return consignment.getStatus() == ConsignmentStatus.ABERTA && now.isAfter(consignment.getDueAt());
    }

    private static String visualStatus(ConsignmentEntity consignment, Instant now) {
        if (consignment.getStatus() == ConsignmentStatus.QUITADA) {
            return "quitada";
        }
        return isLate(consignment, now) ? "atrasada" : "em_dia";
    }

    private static ConsignmentStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ConsignmentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw validation("status", "Use aberta ou quitada.");
        }
    }

    private static ApiException inconsistent(List<PieceEntity> pieces) {
        String codes = pieces.stream().map(PieceEntity::getCode).collect(Collectors.joining(", "));
        return new ApiException(HttpStatus.CONFLICT, "PIECE_STATE_INCONSISTENT",
                "O estado das peças " + codes + " está inconsistente entre estoque e lotes. "
                        + "Nenhuma alteração foi feita; avise o responsável pelo sistema.",
                Map.of("pieceIds", pieces.stream().map(piece -> piece.getId().toString()).toList()));
    }

    private static ApiException consignmentNotFound(UUID consignmentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "CONSIGNMENT_NOT_FOUND",
                "O lote " + consignmentId + " não foi encontrado.");
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message,
                Map.of(field, List.of(message)));
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record ItemAction(ConsignmentEntity consignment, ConsignmentItemEntity item, PieceEntity piece) {
    }

    record ItemActionFingerprint(UUID consignmentId, UUID pieceId, Instant at, String note) {
    }
}
