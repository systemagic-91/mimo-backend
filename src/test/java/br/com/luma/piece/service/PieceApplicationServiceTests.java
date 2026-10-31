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

import com.fasterxml.jackson.databind.ObjectMapper;

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
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.upload.service.PhotoApplicationService;

@ExtendWith(MockitoExtension.class)
class PieceApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();

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
    private JdbcTemplate jdbcTemplate;

    private PieceApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PieceApplicationService(pieceRepository, pieceEventRepository, userRepository, pieceMapper,
                pieceEventMapper, idempotencyService, photoService, jdbcTemplate, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(pieceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(pieceEventRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void receivingFromFactoryAllocatesCodeAndRecordsOneEvent() {
        allocateSequence(7L);

        IdempotentResponse response = service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest(" Brinco ", 12_900, PieceStage.FABRICA, "  "));

        assertThat(response.status()).isEqualTo(201);
        PieceEntity piece = savedPiece();
        assertThat(piece.getCode()).isEqualTo("LU-0007");
        assertThat(piece.getType()).isEqualTo("Brinco");
        assertThat(piece.getStatus()).isEqualTo(PieceStage.FABRICA);
        assertThat(piece.getCurrentLocation()).isEqualTo("Fábrica");
        assertThat(piece.getNote()).isNull();
        assertThat(savedEvents()).extracting(PieceEventEntity::getStage).containsExactly(PieceStage.FABRICA);
    }

    @Test
    void receivingAtTheBathRecordsFactoryThenBathInOrder() {
        allocateSequence(12L);

        service.receive(ACTOR_ID, "key", new ReceivePieceRequest("Colar", 5_000, PieceStage.BANHO_OURO, null));

        assertThat(savedPiece().getCurrentLocation()).isEqualTo("No banho de ouro");
        List<PieceEventEntity> events = savedEvents();
        assertThat(events).extracting(PieceEventEntity::getStage)
                .containsExactly(PieceStage.FABRICA, PieceStage.BANHO_OURO);
        assertThat(events.get(0).getOccurredAt()).isBefore(events.get(1).getOccurredAt());
    }

    @Test
    void receivingRejectsStagesOtherThanFactoryOrBath() {
        assertApiError(() -> service.receive(ACTOR_ID, "key",
                new ReceivePieceRequest("Anel", 1_000, PieceStage.ETIQUETADA, null)),
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
                "Pulseira", 25_000, photoId, " Retorno ok ", true));

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
    }

    @Test
    void labelEventIsOrderedAfterTheLatestExistingEvent() {
        PieceEntity piece = piece(PieceStage.BANHO_OURO);
        PieceEventEntity latest = new PieceEventEntity();
        latest.setOccurredAt(NOW.plusSeconds(5));
        when(pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(piece.getId()))
                .thenReturn(Optional.of(latest));

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 1_000, null, null, true));

        ArgumentCaptor<PieceEventEntity> event = ArgumentCaptor.forClass(PieceEventEntity.class);
        verify(pieceEventRepository).save(event.capture());
        assertThat(event.getValue().getOccurredAt()).isEqualTo(NOW.plusSeconds(5).plusMillis(1));
    }

    @Test
    void labelingRequiresBathReturnAndBathStage() {
        PieceEntity labeled = piece(PieceStage.ETIQUETADA);

        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(labeled.getId(), "Anel", 1_000, null, null, false)),
                HttpStatus.CONFLICT, "INVALID_PIECE_TRANSITION");
        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(labeled.getId(), "Anel", 1_000, null, null, true)),
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
                new LabelPieceRequest(piece.getId(), "Anel", 1_000, missingPhoto, null, true)),
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

        service.label(ACTOR_ID, "key", new LabelPieceRequest(piece.getId(), "Anel", 1_000, null, null, true));

        assertThat(piece.getPhotoId()).isNull();
        verify(photoService, never()).requireExisting(any());
    }

    @Test
    void labelingUnknownPieceReturnsNotFound() {
        UUID missing = UUID.randomUUID();
        when(pieceRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertApiError(() -> service.label(ACTOR_ID, "key",
                new LabelPieceRequest(missing, "Anel", 1_000, null, null, true)),
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
        when(pieceRepository.findByIdForUpdate(piece.getId())).thenReturn(Optional.of(piece));
        return piece;
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
