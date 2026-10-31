package br.com.luma.piece.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.piece.dto.LabelPieceRequest;
import br.com.luma.piece.dto.ReceivePieceRequest;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.piece.mapper.PieceEventMapper;
import br.com.luma.piece.mapper.PieceMapper;
import br.com.luma.piece.repository.PieceEventRepository;
import br.com.luma.piece.repository.PieceRepository;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.audit.AuditTrail;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.PieceResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.metrics.BusinessMetrics;
import br.com.luma.upload.service.PhotoApplicationService;

@ExtendWith(MockitoExtension.class)
class PieceApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.10";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Mock
    private PieceRepository pieceRepository;
    @Mock
    private PieceEventRepository pieceEventRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PieceMapper pieceMapper;
    @Mock
    private PieceEventMapper pieceEventMapper;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private PhotoApplicationService photoService;
    @Mock
    private AuditTrail auditTrail;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PieceCostVisibility costVisibility;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PieceApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PieceApplicationService(pieceRepository, pieceEventRepository, userRepository, pieceMapper,
                pieceEventMapper, idempotencyService, photoService, auditTrail, new BusinessMetrics(meterRegistry),
                costVisibility, jdbcTemplate, OBJECT_MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(pieceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(pieceEventRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(pieceMapper.toResponse(any())).thenAnswer(invocation -> {
            PieceEntity piece = invocation.getArgument(0);
            return new PieceResponse(piece.getId(), piece.getCode(), piece.getType(), piece.getValueCents(),
                    piece.getStatus(), piece.getCurrentLocation(), null, piece.getNote(), piece.getFactoryCostCents(),
                    piece.getBathCostCents(), PieceCosts.total(piece.getFactoryCostCents(), piece.getBathCostCents()),
                    NOW, NOW, 0);
        });
    }

    @Test
    void receivingFromFactoryAllocatesCodeAndRecordsOneEvent() {
        allocateSequence(7L);

        IdempotentResponse response = service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest(" Brinco ", 12_900, PieceStage.FABRICA, "  ", null), IP);

        assertThat(response.status()).isEqualTo(201);
        PieceEntity piece = savedPiece();
        assertThat(piece.getCode()).isEqualTo("LU-0007");
        assertThat(piece.getType()).isEqualTo("Brinco");
        assertThat(piece.getStatus()).isEqualTo(PieceStage.FABRICA);
        assertThat(piece.getCurrentLocation()).isEqualTo("Fábrica");
        assertThat(piece.getNote()).isNull();
        assertThat(savedEvents()).extracting(PieceEventEntity::getStage).containsExactly(PieceStage.FABRICA);
        verify(auditTrail).record(eq(ACTOR_ID), eq("PIECE_RECEIVED"), eq("PIECE"), any(), eq(IP), any());
        assertThat(meterRegistry.get("luma.pieces.movements").tag("action", "received").counter().count())
                .isEqualTo(1);
    }

    @Test
    void receivingAtTheBathRecordsFactoryThenBathInOrder() {
        allocateSequence(12L);

        service.receive(ACTOR_ID, "key", new ReceivePieceRequest("Colar", 5_000, PieceStage.BANHO_OURO, null, null), IP);

        assertThat(savedPiece().getCurrentLocation()).isEqualTo("No banho de ouro");
        List<PieceEventEntity> events = savedEvents();
        assertThat(events).extracting(PieceEventEntity::getStage)
                .containsExactly(PieceStage.FABRICA, PieceStage.BANHO_OURO);
        assertThat(events.get(0).getOccurredAt()).isBefore(events.get(1).getOccurredAt());
    }

    @Test
    void receivingRejectsStagesOtherThanFactoryOrBath() {
        assertApiError(() -> service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest("Anel", 1_000, PieceStage.ETIQUETADA, null, null), IP),
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INITIAL_STAGE");
        verify(pieceRepository, never()).save(any());
    }

    @Test
    void labelingUpdatesTheSamePieceAndKeepsItsCode() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        UUID photoId = UUID.randomUUID();
        when(pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(piece.getId()))
                .thenReturn(Optional.empty());

        IdempotentResponse response = service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(),
                "Pulseira", 25_000, photoId, " Retorno ok ", true, null), IP);

        assertThat(response.status()).isEqualTo(200);
        verify(pieceRepository).save(piece);
        assertThat(piece.getCode()).isEqualTo("LU-0001");
        assertThat(piece.getStatus()).isEqualTo(PieceStage.ETIQUETADA);
        assertThat(piece.getCurrentLocation()).isEqualTo("Estoque");
        assertThat(piece.getValueCents()).isEqualTo(25_000);
        verify(photoService).requireExisting(photoId);
        assertThat(piece.getPhotoId()).isEqualTo(photoId);
        assertThat(piece.getNote()).isEqualTo("Retorno ok");
        ArgumentCaptor<PieceEventEntity> event = ArgumentCaptor.forClass(PieceEventEntity.class);
        verify(pieceEventRepository).save(event.capture());
        assertThat(event.getValue().getStage()).isEqualTo(PieceStage.ETIQUETADA);
        assertThat(event.getValue().getOccurredAt()).isEqualTo(NOW);
        verify(auditTrail).record(eq(ACTOR_ID), eq("PIECE_LABELED"), eq("PIECE"), eq(piece.getId()), eq(IP), any());
        assertThat(meterRegistry.get("luma.pieces.movements").tag("action", "labeled").counter().count())
                .isEqualTo(1);
    }

    @Test
    void labelEventIsOrderedAfterTheLatestExistingEvent() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        PieceEventEntity latest = new PieceEventEntity();
        latest.setOccurredAt(NOW.plusSeconds(5));
        when(pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(piece.getId()))
                .thenReturn(Optional.of(latest));

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 1_000, null, null, true, null), IP);

        ArgumentCaptor<PieceEventEntity> event = ArgumentCaptor.forClass(PieceEventEntity.class);
        verify(pieceEventRepository).save(event.capture());
        assertThat(event.getValue().getOccurredAt()).isEqualTo(NOW.plusSeconds(5).plusMillis(1));
    }

    @Test
    void labelingRequiresBathReturnAndBathStage() {
        PieceEntity labeled = piece(PieceStage.ETIQUETADA);

        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(labeled.getId(), "Anel", 1_000, null, null, false, null), IP),
                HttpStatus.CONFLICT, "INVALID_PIECE_TRANSITION");
        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(labeled.getId(), "Anel", 1_000, null, null, true, null), IP),
                HttpStatus.CONFLICT, "INVALID_PIECE_TRANSITION");
        verify(pieceRepository, never()).save(any());
    }

    @Test
    void labelingWithUnknownPhotoFailsWithoutSavingThePiece() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        UUID missingPhoto = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "missing"))
                .when(photoService).requireExisting(missingPhoto);

        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(piece.getId(), "Anel", 1_000, missingPhoto, null, true, null), IP),
                HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
        assertThat(piece.getStatus()).isEqualTo(PieceStage.BANHO_OURO);
        assertThat(piece.getPhotoId()).isNull();
        verify(pieceRepository, never()).save(any());
    }

    @Test
    void labelingWithoutPhotoKeepsThePieceWithoutPhoto() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        when(pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(piece.getId()))
                .thenReturn(Optional.empty());

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 1_000, null, null, true, null), IP);

        assertThat(piece.getPhotoId()).isNull();
        verify(photoService, never()).requireExisting(any());
    }

    @Test
    void labelingUnknownPieceReturnsNotFound() {
        UUID missing = UUID.randomUUID();
        when(pieceRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(missing, "Anel", 1_000, null, null, true, null), IP),
                HttpStatus.NOT_FOUND, "PIECE_NOT_FOUND");
    }

    @Test
    void searchNormalizesBlankQueryAndRejectsLongQuery() {
        when(pieceRepository.search(isNull(), eq(PieceStage.ETIQUETADA), any())).thenReturn(Page.empty());

        service.list("   ", PieceStage.ETIQUETADA, new PaginationRequest());

        assertApiError(() -> service.list("x".repeat(121), null, new PaginationRequest()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    void eventsOfUnknownPieceReturnNotFound() {
        UUID missing = UUID.randomUUID();
        when(pieceRepository.findById(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.events(missing), HttpStatus.NOT_FOUND, "PIECE_NOT_FOUND");
    }

    @Test
    void previewFormatsNextCodeAndRejectsInvalidSequence() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(813L, 0L);

        assertThat(service.previewNextCode()).isEqualTo("LU-0813");
        assertThatThrownBy(service::previewNextCode).isInstanceOf(IllegalStateException.class);
    }

    private void allocateSequence(long value) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(value);
    }

    private PieceEntity piece(PieceStage stage) {
        PieceEntity piece = new PieceEntity();
        piece.setId(UUID.randomUUID());
        piece.setCode("LU-0001");
        piece.setType("Brinco");
        piece.setValueCents(10_000);
        piece.setStatus(stage);
        piece.setCurrentLocation("No banho de ouro");
        lenient().when(pieceRepository.findByIdForUpdate(piece.getId())).thenReturn(Optional.of(piece));
        return piece;
    }

    @Test
    void ownerInformsTheFactoryCostOnReceiptAndTheChangeIsAudited() {
        allocateSequence(3L);
        when(costVisibility.currentUserIsOwner()).thenReturn(true);

        service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest("Brinco", 12_900, PieceStage.FABRICA, null, 3_000L), IP);

        assertThat(savedPiece().getFactoryCostCents()).isEqualTo(3_000L);
        verify(auditTrail).record(eq(ACTOR_ID), eq("PIECE_COSTS_UPDATED"), eq("PIECE"), any(), eq(IP),
                eq(costs(null, null)), eq(costs(3_000L, null)));
    }

    @Test
    void receiptWithoutCostDoesNotAuditCosts() {
        allocateSequence(3L);

        service.receive(ACTOR_ID, "key", new ReceivePieceRequest("Brinco", 12_900, PieceStage.FABRICA, null, null), IP);

        assertThat(savedPiece().getFactoryCostCents()).isNull();
        verify(auditTrail, never()).record(any(), eq("PIECE_COSTS_UPDATED"), any(), any(), any(), any(), any());
    }

    @Test
    void staffSendingACostIsRejectedBeforeAnythingIsSaved() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        assertApiError(() -> service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest("Brinco", 12_900, PieceStage.FABRICA, null, 3_000L), IP),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(piece.getId(), "Anel", 1_000, null, null, true, 0L), IP),
                HttpStatus.FORBIDDEN, "FORBIDDEN");

        verify(idempotencyService, never()).execute(any(), anyString(), anyString(), any(), any());
        verify(pieceRepository, never()).save(any());
    }

    @Test
    void ownerInformsTheBathCostOnLabelingKeepingTheFactoryCost() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        piece.setFactoryCostCents(3_000L);
        when(costVisibility.currentUserIsOwner()).thenReturn(true);

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 9_000, null, null, true, 800L), IP);

        assertThat(piece.getFactoryCostCents()).isEqualTo(3_000L);
        assertThat(piece.getBathCostCents()).isEqualTo(800L);
        verify(auditTrail).record(ACTOR_ID, "PIECE_COSTS_UPDATED", "PIECE", piece.getId(), IP,
                costs(3_000L, null), costs(3_000L, 800L));
    }

    @Test
    void staffLabelingKeepsTheCostsTheOwnerAlreadyInformed() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        piece.setFactoryCostCents(3_000L);
        piece.setBathCostCents(800L);

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 9_000, null, null, true, null), IP);

        assertThat(piece.getFactoryCostCents()).isEqualTo(3_000L);
        assertThat(piece.getBathCostCents()).isEqualTo(800L);
    }

    @Test
    void staffNeverSeesCostsInDetailListOrMutationResponses() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        when(pieceRepository.search(any(), any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(piece)));
        piece.setFactoryCostCents(3_000L);
        piece.setBathCostCents(800L);

        assertThat(service.get(piece.getId()).totalCostCents()).isNull();
        assertThat(service.list(null, null, new PaginationRequest()).items())
                .allSatisfy(item -> {
                    assertThat(item.factoryCostCents()).isNull();
                    assertThat(item.bathCostCents()).isNull();
                    assertThat(item.totalCostCents()).isNull();
                });
        IdempotentResponse labeled = service.label(ACTOR_ID, "key",
                new LabelPieceRequest(piece.getId(), "Anel", 9_000, null, null, true, null), IP);
        assertThat(labeled.body().get("factoryCostCents").isNull()).isTrue();
        assertThat(labeled.body().get("totalCostCents").isNull()).isTrue();
    }

    @Test
    void ownerSeesCosts() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        piece.setFactoryCostCents(3_000L);
        piece.setBathCostCents(800L);
        when(costVisibility.currentUserIsOwner()).thenReturn(true);

        PieceResponse response = service.get(piece.getId());

        assertThat(response.factoryCostCents()).isEqualTo(3_000L);
        assertThat(response.totalCostCents()).isEqualTo(3_800L);
    }

    @Test
    void costPatchChangesOnlyTheFieldsSentAndNullClears() throws Exception {
        PieceEntity piece = piece(PieceStage.ETIQUETADA);
        piece.setFactoryCostCents(3_000L);
        piece.setBathCostCents(800L);

        service.updateCosts(ACTOR_ID, piece.getId(), "key", json("{\"bathCostCents\": 950}"), IP);
        assertThat(piece.getFactoryCostCents()).isEqualTo(3_000L);
        assertThat(piece.getBathCostCents()).isEqualTo(950L);
        verify(auditTrail).record(ACTOR_ID, "PIECE_COSTS_UPDATED", "PIECE", piece.getId(), IP,
                costs(3_000L, 800L), costs(3_000L, 950L));

        service.updateCosts(ACTOR_ID, piece.getId(), "key-2", json("{\"factoryCostCents\": null}"), IP);
        assertThat(piece.getFactoryCostCents()).isNull();
        assertThat(piece.getBathCostCents()).isEqualTo(950L);
    }

    @Test
    void costPatchWithoutChangesIsNotAudited() throws Exception {
        PieceEntity piece = piece(PieceStage.ETIQUETADA);
        piece.setFactoryCostCents(3_000L);

        service.updateCosts(ACTOR_ID, piece.getId(), "key", json("{\"factoryCostCents\": 3000}"), IP);

        verify(auditTrail, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void costPatchRejectsInvalidBodies() {
        for (String body : List.of("{}", "{\"factoryCostCents\": -1}", "{\"bathCostCents\": 1.5}",
                "{\"bathCostCents\": \"10\"}", "{\"valueCents\": 10}", "[]")) {
            assertApiError(() -> service.updateCosts(ACTOR_ID, UUID.randomUUID(), "key", json(body), IP),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        verify(idempotencyService, never()).execute(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void costPatchForMissingPieceIsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        when(pieceRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.updateCosts(ACTOR_ID, missing, "key", json("{\"bathCostCents\": 1}"), IP),
                HttpStatus.NOT_FOUND, "PIECE_NOT_FOUND");
    }

    private static JsonNode json(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static java.util.Map<String, Object> costs(Long factoryCostCents, Long bathCostCents) {
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("factoryCostCents", factoryCostCents);
        state.put("bathCostCents", bathCostCents);
        return state;
    }


    private PieceEntity savedPiece() {
        ArgumentCaptor<PieceEntity> piece = ArgumentCaptor.forClass(PieceEntity.class);
        verify(pieceRepository).save(piece.capture());
        return piece.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<PieceEventEntity> savedEvents() {
        ArgumentCaptor<List<PieceEventEntity>> events = ArgumentCaptor.forClass(List.class);
        verify(pieceEventRepository).saveAll(events.capture());
        return events.getValue();
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
