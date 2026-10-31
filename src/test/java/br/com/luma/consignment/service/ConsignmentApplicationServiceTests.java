package br.com.luma.consignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.consignment.dto.ConsignmentListItemResponse;
import br.com.luma.consignment.dto.CreateConsignmentRequest;
import br.com.luma.consignment.dto.MarkItemReturnedRequest;
import br.com.luma.consignment.dto.MarkItemSoldRequest;
import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.consignment.mapper.ConsignmentItemMapperImpl;
import br.com.luma.consignment.repository.ConsignmentItemRepository;
import br.com.luma.consignment.repository.ConsignmentItemRepository.InHandSummary;
import br.com.luma.consignment.repository.ConsignmentRepository;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.piece.repository.PieceEventRepository;
import br.com.luma.piece.repository.PieceRepository;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.seller.mapper.SellerMapperImpl;
import br.com.luma.seller.repository.SellerRepository;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.ConsignmentItemStatus;
import br.com.luma.shared.ConsignmentStatus;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.metrics.BusinessMetrics;
import br.com.luma.shared.security.CurrentUserRoles;
import br.com.luma.shared.repository.AuditLogRepository;

@ExtendWith(MockitoExtension.class)
class ConsignmentApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.10";

    @Mock
    private ConsignmentRepository consignmentRepository;
    @Mock
    private ConsignmentItemRepository itemRepository;
    @Mock
    private SellerRepository sellerRepository;
    @Mock
    private PieceRepository pieceRepository;
    @Mock
    private PieceEventRepository pieceEventRepository;
    @Mock
    private SettingsRepository settingsRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private CurrentUserRoles currentUserRoles;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ConsignmentApplicationService service;
    private UserEntity actor;

    @BeforeEach
    void setUp() {
        service = new ConsignmentApplicationService(consignmentRepository, itemRepository, sellerRepository,
                pieceRepository, pieceEventRepository, settingsRepository, userRepository, auditLogRepository,
                new ConsignmentItemMapperImpl(), new SellerMapperImpl(), idempotencyService,
                new BusinessMetrics(meterRegistry), currentUserRoles, jdbcTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule())
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), Clock.fixed(NOW, ZoneOffset.UTC));
        actor = new UserEntity();
        actor.setId(ACTOR_ID);
        lenient().when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(settingsRepository.findFirstBySingletonKeyTrue()).thenReturn(Optional.of(settings()));
        lenient().when(consignmentRepository.save(any())).thenAnswer(invocation -> {
            ConsignmentEntity consignment = invocation.getArgument(0);
            if (consignment.getId() == null) {
                consignment.setId(UUID.randomUUID());
            }
            return consignment;
        });
        lenient().when(itemRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ConsignmentItemEntity> items = invocation.getArgument(0);
            items.forEach(item -> item.setId(UUID.randomUUID()));
            return items;
        });
        lenient().when(pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void createDeliversAllPiecesWithSnapshotsEventsAndAudit() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 35);
        PieceEntity first = piece("LU-0002", PieceStage.ETIQUETADA);
        PieceEntity second = piece("LU-0001", PieceStage.ETIQUETADA);
        lockPieces(first, second);
        allocateCode(7L);

        IdempotentResponse response = service.create(ACTOR_ID, "key",
                new CreateConsignmentRequest(seller.getId(), List.of(first.getId(), second.getId()),
                        TODAY.plusDays(30), 30, " Lote de outubro "), IP);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("code").asText()).isEqualTo("C-2026-007");
        assertThat(response.body().get("items")).hasSize(2);
        assertThat(response.body().get("items").get(0).get("codeSnapshot").asText()).isEqualTo("LU-0001");

        ConsignmentEntity consignment = savedConsignment();
        assertThat(consignment.getStatus()).isEqualTo(ConsignmentStatus.ABERTA);
        assertThat(consignment.getDeliveredAt()).isEqualTo(NOW);
        assertThat(consignment.getDueAt()).isEqualTo(Instant.parse("2026-11-30T02:59:59.999999Z"));
        assertThat(consignment.getNote()).isEqualTo("Lote de outubro");

        List<ConsignmentItemEntity> items = savedItems();
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getStatus()).isEqualTo(ConsignmentItemStatus.COM_VENDEDORA);
            assertThat(item.getCommissionRateSnapshot()).isEqualTo(35);
            assertThat(item.getValueCentsSnapshot()).isEqualTo(10_000);
        });
        assertThat(first.getStatus()).isEqualTo(PieceStage.CONSIGNACAO);
        assertThat(first.getCurrentLocation()).isEqualTo("Com: Ana");
        assertThat(savedEvents()).extracting(PieceEventEntity::getStage)
                .containsExactly(PieceStage.CONSIGNACAO, PieceStage.CONSIGNACAO);
        assertThat(savedAudit().getAction()).isEqualTo("CONSIGNMENT_CREATED");
        assertThat(meterRegistry.get("luma.consignments.created").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("luma.consignment.items").tag("action", "delivered").counter().count())
                .isEqualTo(2);
    }

    @Test
    void createLocksPiecesInDeterministicOrder() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(pieceRepository.findAllForUpdate(List.of(high, low).stream().sorted().toList())).thenReturn(List.of());

        assertApiError(() -> create(seller.getId(), List.of(high, low), TODAY), HttpStatus.NOT_FOUND,
                "PIECE_NOT_FOUND");
    }

    @Test
    void dueTodayIsAcceptedAndDueYesterdayIsRejected() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        PieceEntity piece = piece("LU-0001", PieceStage.ETIQUETADA);
        lockPieces(piece);
        allocateCode(1L);

        service.create(ACTOR_ID, "key",
                new CreateConsignmentRequest(seller.getId(), List.of(piece.getId()), TODAY, 30, null), IP);
        assertThat(savedConsignment().getDueAt()).isEqualTo(Instant.parse("2026-10-31T02:59:59.999999Z"));

        assertApiError(() -> create(seller.getId(), List.of(piece.getId()), TODAY.minusDays(1)),
                HttpStatus.UNPROCESSABLE_ENTITY, "DUE_DATE_IN_PAST");
    }

    @Test
    void createRejectsEmptyAndDuplicatedPiecesBeforeTouchingTheDatabase() {
        UUID sellerId = UUID.randomUUID();
        UUID pieceId = UUID.randomUUID();

        assertApiError(() -> create(sellerId, List.of(), TODAY), HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_ITEMS");
        assertApiError(() -> create(sellerId, List.of(pieceId, pieceId), TODAY), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
        verifyNoInteractions(sellerRepository, pieceRepository, consignmentRepository, jdbcTemplate);
    }

    @Test
    void createRejectsInactiveOrUnknownSeller() {
        SellerEntity inactive = seller(SellerStatus.INATIVA, 30);
        UUID unknown = UUID.randomUUID();
        when(sellerRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        assertApiError(() -> create(inactive.getId(), List.of(UUID.randomUUID()), TODAY),
                HttpStatus.UNPROCESSABLE_ENTITY, "SELLER_INACTIVE");
        assertApiError(() -> create(unknown, List.of(UUID.randomUUID()), TODAY), HttpStatus.NOT_FOUND,
                "SELLER_NOT_FOUND");
        verifyNoInteractions(pieceRepository, consignmentRepository);
    }

    @Test
    void unavailablePieceAbortsTheWholeLotWithoutAnyWrite() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        PieceEntity available = piece("LU-0001", PieceStage.ETIQUETADA);
        PieceEntity sold = piece("LU-0002", PieceStage.VENDIDA);
        lockPieces(available, sold);

        assertThatThrownBy(() -> create(seller.getId(), List.of(available.getId(), sold.getId()), TODAY))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("PIECE_NOT_AVAILABLE");
                    assertThat(exception.getMessage()).contains("LU-0002").doesNotContain("LU-0001");
                });
        assertThat(available.getStatus()).isEqualTo(PieceStage.ETIQUETADA);
        assertNothingWritten();
        assertThat(meterRegistry.find("luma.consignments.created").counter()).isNull();
    }

    @Test
    void pieceStillActiveInAnotherLotIsReportedAsInconsistent() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        PieceEntity piece = piece("LU-0001", PieceStage.ETIQUETADA);
        lockPieces(piece);
        ConsignmentItemEntity active = new ConsignmentItemEntity();
        active.setPiece(piece);
        when(itemRepository.findAllByPiece_IdInAndStatus(List.of(piece.getId()), ConsignmentItemStatus.COM_VENDEDORA))
                .thenReturn(List.of(active));

        assertApiError(() -> create(seller.getId(), List.of(piece.getId()), TODAY), HttpStatus.CONFLICT,
                "PIECE_STATE_INCONSISTENT");
        assertNothingWritten();
    }

    @Test
    void idempotentRetryReturnsSavedResponseWithoutRunningTheOperationAgain() {
        doReturn(new IdempotentResponse(201, JsonNodeFactory.instance.objectNode().put("code", "C-2026-001")))
                .when(idempotencyService).execute(eq(ACTOR_ID), eq("consignments.create"), eq("retry"), any(), any());

        IdempotentResponse response = service.create(ACTOR_ID, "retry",
                new CreateConsignmentRequest(UUID.randomUUID(), List.of(UUID.randomUUID()), TODAY, 30, null), IP);

        assertThat(response.body().get("code").asText()).isEqualTo("C-2026-001");
        verifyNoInteractions(sellerRepository, pieceRepository, consignmentRepository, jdbcTemplate);
    }

    @Test
    void sellingAnItemUpdatesItemPieceAndTimelineAndKeepsLotOpen() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        when(itemRepository.countByConsignment_IdAndStatus(fixture.consignment.getId(),
                ConsignmentItemStatus.COM_VENDEDORA)).thenReturn(1L);

        IdempotentResponse response = service.markSold(ACTOR_ID, "key", fixture.consignment.getId(),
                fixture.piece.getId(), null, IP);

        assertThat(response.status()).isEqualTo(200);
        assertThat(fixture.item.getStatus()).isEqualTo(ConsignmentItemStatus.VENDIDA);
        assertThat(fixture.item.getSoldAt()).isEqualTo(NOW);
        assertThat(fixture.piece.getStatus()).isEqualTo(PieceStage.VENDIDA);
        assertThat(fixture.piece.getCurrentLocation()).isEqualTo("Vendida");
        assertThat(fixture.consignment.getStatus()).isEqualTo(ConsignmentStatus.ABERTA);
        ArgumentCaptor<PieceEventEntity> event = ArgumentCaptor.forClass(PieceEventEntity.class);
        verify(pieceEventRepository).save(event.capture());
        assertThat(event.getValue().getStage()).isEqualTo(PieceStage.VENDIDA);
        assertThat(savedAudit().getAction()).isEqualTo("CONSIGNMENT_ITEM_SOLD");
        assertThat(meterRegistry.get("luma.consignment.items").tag("action", "sold").counter().count())
                .isEqualTo(1);
    }

    @Test
    void lastSaleSettlesTheLot() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        when(itemRepository.countByConsignment_IdAndStatus(fixture.consignment.getId(),
                ConsignmentItemStatus.COM_VENDEDORA)).thenReturn(0L);

        IdempotentResponse response = service.markSold(ACTOR_ID, "key", fixture.consignment.getId(),
                fixture.piece.getId(), new MarkItemSoldRequest(null, "Pix"), IP);

        assertThat(fixture.consignment.getStatus()).isEqualTo(ConsignmentStatus.QUITADA);
        assertThat(response.body().get("status").asText()).isEqualTo("QUITADA");
    }

    @Test
    void retroactiveSaleMustBeBetweenDeliveryAndNow() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        UUID consignmentId = fixture.consignment.getId();
        UUID pieceId = fixture.piece.getId();

        assertApiError(() -> service.markSold(ACTOR_ID, "a", consignmentId, pieceId,
                new MarkItemSoldRequest(NOW.plusSeconds(60), null), IP), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertApiError(() -> service.markSold(ACTOR_ID, "b", consignmentId, pieceId,
                new MarkItemSoldRequest(fixture.consignment.getDeliveredAt().minusSeconds(1), null), IP),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fixture.item.getStatus()).isEqualTo(ConsignmentItemStatus.COM_VENDEDORA);

        Instant yesterday = NOW.minusSeconds(24 * 60 * 60);
        service.markSold(ACTOR_ID, "c", consignmentId, pieceId, new MarkItemSoldRequest(yesterday, null), IP);
        assertThat(fixture.item.getSoldAt()).isEqualTo(yesterday);
    }

    @Test
    void itemAlreadySoldOrReturnedIsRejectedWithoutNewEvents() {
        ItemFixture sold = itemFixture(ConsignmentItemStatus.VENDIDA, PieceStage.VENDIDA);

        assertThatThrownBy(() -> service.markSold(ACTOR_ID, "key", sold.consignment.getId(), sold.piece.getId(),
                null, IP)).isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("ITEM_NOT_WITH_SELLER");
                    assertThat(exception.getMessage()).contains("vendida");
                });
        assertApiError(() -> service.markReturned(ACTOR_ID, "other", sold.consignment.getId(),
                sold.piece.getId(), null, IP), HttpStatus.CONFLICT, "ITEM_NOT_WITH_SELLER");
        verify(pieceEventRepository, never()).save(any());
        verify(pieceEventRepository, never()).saveAll(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void pieceOutsideConsignmentStageBlocksTheAction() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.ETIQUETADA);

        assertApiError(() -> service.markSold(ACTOR_ID, "key", fixture.consignment.getId(),
                fixture.piece.getId(), null, IP), HttpStatus.CONFLICT, "PIECE_STATE_INCONSISTENT");
        assertThat(fixture.item.getStatus()).isEqualTo(ConsignmentItemStatus.COM_VENDEDORA);
    }

    @Test
    void unknownLotOrPieceOutsideTheLotReturnNotFound() {
        UUID missingLot = UUID.randomUUID();
        when(consignmentRepository.findByIdForUpdate(missingLot)).thenReturn(Optional.empty());
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        UUID otherPiece = UUID.randomUUID();
        when(itemRepository.findForUpdate(fixture.consignment.getId(), otherPiece)).thenReturn(Optional.empty());

        assertApiError(() -> service.markSold(ACTOR_ID, "a", missingLot, UUID.randomUUID(), null, IP),
                HttpStatus.NOT_FOUND, "CONSIGNMENT_NOT_FOUND");
        assertApiError(() -> service.markSold(ACTOR_ID, "b", fixture.consignment.getId(), otherPiece, null, IP),
                HttpStatus.NOT_FOUND, "CONSIGNMENT_ITEM_NOT_FOUND");
    }

    @Test
    void returningBringsPieceBackToStockWithOrderedEventsAndSettlesTheLot() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        when(itemRepository.countByConsignment_IdAndStatus(fixture.consignment.getId(),
                ConsignmentItemStatus.COM_VENDEDORA)).thenReturn(0L);

        service.markReturned(ACTOR_ID, "key", fixture.consignment.getId(), fixture.piece.getId(),
                new MarkItemReturnedRequest(null, null), IP);

        assertThat(fixture.item.getStatus()).isEqualTo(ConsignmentItemStatus.DEVOLVIDA);
        assertThat(fixture.item.getReturnedAt()).isEqualTo(NOW);
        assertThat(fixture.piece.getStatus()).isEqualTo(PieceStage.ETIQUETADA);
        assertThat(fixture.piece.getCurrentLocation()).isEqualTo("Estoque");
        assertThat(fixture.consignment.getStatus()).isEqualTo(ConsignmentStatus.QUITADA);
        List<PieceEventEntity> events = savedEvents();
        assertThat(events).extracting(PieceEventEntity::getStage)
                .containsExactly(PieceStage.DEVOLVIDA, PieceStage.ETIQUETADA);
        assertThat(events.get(0).getOccurredAt()).isBefore(events.get(1).getOccurredAt());
        assertThat(savedAudit().getAction()).isEqualTo("CONSIGNMENT_ITEM_RETURNED");
    }

    @Test
    void listShowsVisualStatusAndValueInHand() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        ConsignmentEntity late = consignment(seller, ConsignmentStatus.ABERTA, NOW.minusSeconds(60));
        ConsignmentEntity onTime = consignment(seller, ConsignmentStatus.ABERTA, NOW.plusSeconds(60));
        ConsignmentEntity settled = consignment(seller, ConsignmentStatus.QUITADA, NOW.minusSeconds(60));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(consignmentRepository.search(eq(null), eq(null), eq(1), eq(NOW), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of(late, onTime, settled)));
        when(itemRepository.summarizeInHand(List.of(late.getId(), onTime.getId(), settled.getId())))
                .thenReturn(List.of(summary(late.getId(), 2, 30_000)));

        List<ConsignmentListItemResponse> items = service.list(null, null, true, new PaginationRequest()).items();

        assertThat(items).extracting(ConsignmentListItemResponse::status)
                .containsExactly("atrasada", "em_dia", "quitada");
        assertThat(items.get(0).piecesInHand()).isEqualTo(2);
        assertThat(items.get(0).valueInHandCents()).isEqualTo(30_000);
        assertThat(items.get(1).piecesInHand()).isZero();
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "deliveredAt"));
    }

    @Test
    void listRejectsUnknownStatus() {
        assertApiError(() -> service.list("pausada", null, null, new PaginationRequest()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    private void create(UUID sellerId, List<UUID> pieceIds, LocalDate dueDate) {
        service.create(ACTOR_ID, "key", new CreateConsignmentRequest(sellerId, pieceIds, dueDate, 30, null), IP);
    }

    private SellerEntity seller(SellerStatus status, int commissionRate) {
        SellerEntity seller = new SellerEntity();
        seller.setId(UUID.randomUUID());
        seller.setName("Ana");
        seller.setPhone("11");
        seller.setCommissionRate(commissionRate);
        seller.setStatus(status);
        lenient().when(sellerRepository.findByIdForUpdate(seller.getId())).thenReturn(Optional.of(seller));
        return seller;
    }

    private static PieceEntity piece(String code, PieceStage stage) {
        PieceEntity piece = new PieceEntity();
        piece.setId(UUID.randomUUID());
        piece.setCode(code);
        piece.setType("Brinco");
        piece.setValueCents(10_000);
        piece.setStatus(stage);
        piece.setCurrentLocation("Estoque");
        return piece;
    }

    private void lockPieces(PieceEntity... pieces) {
        List<PieceEntity> sorted = new ArrayList<>(List.of(pieces));
        sorted.sort((left, right) -> left.getId().compareTo(right.getId()));
        when(pieceRepository.findAllForUpdate(sorted.stream().map(PieceEntity::getId).toList())).thenReturn(sorted);
    }

    private void allocateCode(long value) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class), anyInt())).thenReturn(value);
    }

    private ConsignmentEntity consignment(SellerEntity seller, ConsignmentStatus status, Instant dueAt) {
        ConsignmentEntity consignment = new ConsignmentEntity();
        consignment.setId(UUID.randomUUID());
        consignment.setCode("C-2026-001");
        consignment.setSeller(seller);
        consignment.setDeliveredAt(NOW.minusSeconds(10 * 24 * 60 * 60));
        consignment.setDueAt(dueAt);
        consignment.setStatus(status);
        consignment.setCreatedBy(actor);
        return consignment;
    }

    @Test
    void createStoresTheChosenPaymentTerm() {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        PieceEntity piece = piece("LU-0001", PieceStage.ETIQUETADA);
        lockPieces(piece);
        allocateCode(1L);

        IdempotentResponse response = service.create(ACTOR_ID, "key",
                new CreateConsignmentRequest(seller.getId(), List.of(piece.getId()), TODAY, 60, null), IP);

        assertThat(savedConsignment().getPaymentTermDays()).isEqualTo(60);
        assertThat(response.body().get("paymentTermDays").asInt()).isEqualTo(60);
        assertThat(savedAudit().getAfterState()).containsEntry("paymentTermDays", 60);
    }

    @Test
    void createRejectsAPaymentTermThatIsNotAllowed() {
        assertThatThrownBy(() -> service.create(ACTOR_ID, "key",
                new CreateConsignmentRequest(UUID.randomUUID(), List.of(UUID.randomUUID()), TODAY, 45, null), IP))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getFields()).containsKey("paymentTermDays");
                });
        verify(consignmentRepository, never()).save(any());
    }

    @Test
    void soldItemShowsWhenAndHowMuchTheSellerOwesToTheOwner() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.COM_VENDEDORA, PieceStage.CONSIGNACAO);
        fixture.consignment.setPaymentTermDays(30);
        fixture.item.setValueCentsSnapshot(10_001);
        when(currentUserRoles.isOwner()).thenReturn(true);
        when(itemRepository.countByConsignment_IdAndStatus(fixture.consignment.getId(),
                ConsignmentItemStatus.COM_VENDEDORA)).thenReturn(0L);

        IdempotentResponse response = service.markSold(ACTOR_ID, "key", fixture.consignment.getId(),
                fixture.piece.getId(), null, IP);

        com.fasterxml.jackson.databind.JsonNode item = response.body().get("items").get(0);
        // Vendida em 2026-10-30 (09:00 em São Paulo) + 30 dias; deve 10.001 − 30% (3.000,3 → 3.000).
        assertThat(item.get("paymentDueDate").asText()).isEqualTo("2026-11-29");
        assertThat(item.get("amountDueCents").asLong()).isEqualTo(7_001);
    }

    @Test
    void dueDateUsesTheStoreDayOfTheSale() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.VENDIDA, PieceStage.VENDIDA);
        fixture.consignment.setPaymentTermDays(60);
        fixture.item.setSoldAt(Instant.parse("2026-10-30T02:30:00Z"));
        when(consignmentRepository.findById(fixture.consignment.getId())).thenReturn(Optional.of(fixture.consignment));

        var details = service.get(fixture.consignment.getId());

        // 23:30 de 29/10 em São Paulo conta como venda do dia 29.
        assertThat(details.items().get(0).paymentDueDate()).isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void staffSeesTheDueDateButNotTheAmountOwed() {
        ItemFixture fixture = itemFixture(ConsignmentItemStatus.VENDIDA, PieceStage.VENDIDA);
        fixture.consignment.setPaymentTermDays(30);
        fixture.item.setSoldAt(NOW);
        when(consignmentRepository.findById(fixture.consignment.getId())).thenReturn(Optional.of(fixture.consignment));

        var item = service.get(fixture.consignment.getId()).items().get(0);

        assertThat(item.paymentDueDate()).isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(item.amountDueCents()).isNull();
    }

    @Test
    void lotsWithoutPaymentTermAndUnsoldItemsHaveNoPaymentData() {
        ItemFixture legacy = itemFixture(ConsignmentItemStatus.VENDIDA, PieceStage.VENDIDA);
        legacy.item.setSoldAt(NOW);
        when(consignmentRepository.findById(legacy.consignment.getId())).thenReturn(Optional.of(legacy.consignment));
        lenient().when(currentUserRoles.isOwner()).thenReturn(true);

        var details = service.get(legacy.consignment.getId());

        assertThat(details.paymentTermDays()).isNull();
        assertThat(details.items().get(0).paymentDueDate()).isNull();
        assertThat(details.items().get(0).amountDueCents()).isNull();

        legacy.consignment.setPaymentTermDays(30);
        legacy.item.setStatus(ConsignmentItemStatus.COM_VENDEDORA);
        legacy.item.setSoldAt(null);
        assertThat(service.get(legacy.consignment.getId()).items().get(0).paymentDueDate()).isNull();
    }

    private ItemFixture itemFixture(ConsignmentItemStatus itemStatus, PieceStage pieceStage) {
        SellerEntity seller = seller(SellerStatus.ATIVA, 30);
        ConsignmentEntity consignment = consignment(seller, ConsignmentStatus.ABERTA, NOW.plusSeconds(3600));
        PieceEntity piece = piece("LU-0001", pieceStage);
        ConsignmentItemEntity item = new ConsignmentItemEntity();
        item.setId(UUID.randomUUID());
        item.setConsignment(consignment);
        item.setPiece(piece);
        item.setCodeSnapshot(piece.getCode());
        item.setTypeSnapshot(piece.getType());
        item.setValueCentsSnapshot(piece.getValueCents());
        item.setCommissionRateSnapshot(30);
        item.setStatus(itemStatus);
        lenient().when(consignmentRepository.findByIdForUpdate(consignment.getId()))
                .thenReturn(Optional.of(consignment));
        lenient().when(itemRepository.findForUpdate(consignment.getId(), piece.getId()))
                .thenReturn(Optional.of(item));
        lenient().when(pieceRepository.findByIdForUpdate(piece.getId())).thenReturn(Optional.of(piece));
        lenient().when(itemRepository.findAllByConsignment_IdOrderByCodeSnapshotAsc(consignment.getId()))
                .thenReturn(List.of(item));
        return new ItemFixture(consignment, item, piece);
    }

    private static InHandSummary summary(UUID consignmentId, long pieces, long value) {
        return new InHandSummary() {
            @Override
            public UUID getConsignmentId() {
                return consignmentId;
            }

            @Override
            public long getPiecesInHand() {
                return pieces;
            }

            @Override
            public long getValueInHandCents() {
                return value;
            }
        };
    }

    private static SettingsEntity settings() {
        SettingsEntity settings = new SettingsEntity();
        settings.setTimezone("America/Sao_Paulo");
        settings.setPaymentTerms(List.of(30, 60, 90));
        return settings;
    }

    private ConsignmentEntity savedConsignment() {
        ArgumentCaptor<ConsignmentEntity> consignment = ArgumentCaptor.forClass(ConsignmentEntity.class);
        verify(consignmentRepository).save(consignment.capture());
        return consignment.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<ConsignmentItemEntity> savedItems() {
        ArgumentCaptor<List<ConsignmentItemEntity>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(items.capture());
        return items.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<PieceEventEntity> savedEvents() {
        ArgumentCaptor<List<PieceEventEntity>> events = ArgumentCaptor.forClass(List.class);
        verify(pieceEventRepository).saveAll(events.capture());
        return events.getValue();
    }

    private AuditLogEntity savedAudit() {
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(audit.capture());
        return audit.getValue();
    }

    private void assertNothingWritten() {
        verify(consignmentRepository, never()).save(any());
        verify(itemRepository, never()).saveAll(any());
        verify(pieceRepository, never()).saveAll(any());
        verify(pieceEventRepository, never()).saveAll(any());
        verify(auditLogRepository, never()).save(any());
        verifyNoInteractions(jdbcTemplate);
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }

    private record ItemFixture(ConsignmentEntity consignment, ConsignmentItemEntity item, PieceEntity piece) {
    }
}
