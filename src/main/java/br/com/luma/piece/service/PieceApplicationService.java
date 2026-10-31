package br.com.luma.piece.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.piece.dto.LabelPieceRequest;
import br.com.luma.piece.dto.ReceivePieceRequest;
import br.com.luma.piece.dto.ReceivePieceResponse;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.piece.mapper.PieceEventMapper;
import br.com.luma.piece.mapper.PieceMapper;
import br.com.luma.piece.repository.PieceEventRepository;
import br.com.luma.piece.repository.PieceRepository;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.PieceEventResponse;
import br.com.luma.shared.dto.PieceResponse;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.upload.service.PhotoApplicationService;

@Service
public class PieceApplicationService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "code", "type", "valueCents", "status", "currentLocation", "createdAt", "updatedAt");

    private final PieceRepository pieceRepository;
    private final PieceEventRepository pieceEventRepository;
    private final UserRepository userRepository;
    private final PieceMapper pieceMapper;
    private final PieceEventMapper pieceEventMapper;
    private final IdempotencyService idempotencyService;
    private final PhotoApplicationService photoService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PieceApplicationService(PieceRepository pieceRepository, PieceEventRepository pieceEventRepository,
            UserRepository userRepository, PieceMapper pieceMapper, PieceEventMapper pieceEventMapper,
            IdempotencyService idempotencyService, PhotoApplicationService photoService, JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper, Clock clock) {
        this.pieceRepository = pieceRepository;
        this.pieceEventRepository = pieceEventRepository;
        this.userRepository = userRepository;
        this.pieceMapper = pieceMapper;
        this.pieceEventMapper = pieceEventMapper;
        this.idempotencyService = idempotencyService;
        this.photoService = photoService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageEnvelope<PieceResponse> list(String query, PieceStage status, PaginationRequest pagination) {
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "A busca deve ter no máximo 120 caracteres.",
                    java.util.Map.of("q", List.of("A busca deve ter no máximo 120 caracteres.")));
        }
        Pageable pageable = pagination.toPageable(ALLOWED_SORT_FIELDS);
        Page<PieceResponse> page = pieceRepository.search(normalizedQuery, status, pageable).map(pieceMapper::toResponse);
        return PageEnvelope.from(page);
    }

    @Transactional(readOnly = true)
    public PieceResponse get(UUID pieceId) {
        return pieceMapper.toResponse(findPiece(pieceId));
    }

    @Transactional(readOnly = true)
    public List<PieceEventResponse> events(UUID pieceId) {
        findPiece(pieceId);
        return pieceEventRepository.findAllByPiece_IdOrderByOccurredAtAscIdAsc(pieceId).stream()
                .map(pieceEventMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public String previewNextCode() {
        Long next = jdbcTemplate.queryForObject(
                "select coalesce((select next_value from code_sequences "
                        + "where scope = 'PIECE' and sequence_year = 0), 1)",
                Long.class);
        return formatPieceCode(next);
    }

    @Transactional
    public IdempotentResponse receive(UUID actorId, String idempotencyKey, ReceivePieceRequest request) {
        return idempotencyService.execute(actorId, "pieces.receive", idempotencyKey, request, () -> {
            if (request.stage() != PieceStage.FABRICA && request.stage() != PieceStage.BANHO_OURO) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INITIAL_STAGE",
                        "O recebimento aceita apenas os estágios FABRICA ou BANHO_OURO.");
            }

            Instant receivedAt = clock.instant();
            PieceEntity piece = new PieceEntity();
            piece.setCode(allocatePieceCode());
            piece.setType(request.type().trim());
            piece.setValueCents(request.valueCents());
            piece.setStatus(request.stage());
            piece.setCurrentLocation(request.stage() == PieceStage.FABRICA ? "Fábrica" : "No banho de ouro");
            piece.setNote(normalizeOptional(request.note()));
            piece = pieceRepository.save(piece);

            List<PieceEventEntity> events = new ArrayList<>();
            if (request.stage() == PieceStage.BANHO_OURO) {
                events.add(createEvent(piece, PieceStage.FABRICA, receivedAt.minusMillis(1),
                        "Recebida da fábrica", actorId));
            }
            events.add(createEvent(piece, request.stage(), receivedAt,
                    request.stage() == PieceStage.FABRICA
                            ? "Recebida da fábrica"
                            : "Encaminhada ao banho de ouro",
                    actorId));

            List<PieceEventResponse> eventResponses = pieceEventRepository.saveAll(events).stream()
                    .map(pieceEventMapper::toResponse)
                    .toList();
            ReceivePieceResponse response = new ReceivePieceResponse(pieceMapper.toResponse(piece), eventResponses);
            return new IdempotentResponse(201, objectMapper.valueToTree(response));
        });
    }

    @Transactional
    public IdempotentResponse label(UUID actorId, String idempotencyKey, LabelPieceRequest request) {
        return idempotencyService.execute(actorId, "pieces.label", idempotencyKey, request, () -> {
            if (!request.backFromBath()) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_PIECE_TRANSITION",
                        "A etiquetagem só pode ser confirmada após o retorno da peça do banho.");
            }

            PieceEntity piece = pieceRepository.findByIdForUpdate(request.sourcePieceId())
                    .orElseThrow(() -> pieceNotFound(request.sourcePieceId()));
            if (piece.getStatus() != PieceStage.BANHO_OURO) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_PIECE_TRANSITION",
                        "A peça precisa estar em BANHO_OURO para ser etiquetada.");
            }
            if (request.photoId() != null) {
                photoService.requireExisting(request.photoId());
            }

            Instant occurredAt = nextEventTime(piece.getId());
            piece.setType(request.type().trim());
            piece.setValueCents(request.valueCents());
            piece.setStatus(PieceStage.ETIQUETADA);
            piece.setCurrentLocation("Estoque");
            if (request.photoId() != null) {
                piece.setPhotoId(request.photoId());
            }
            if (request.note() != null) {
                piece.setNote(normalizeOptional(request.note()));
            }
            piece = pieceRepository.save(piece);

            PieceEventEntity event = createEvent(piece, PieceStage.ETIQUETADA, occurredAt, "Etiquetada", actorId);
            pieceEventRepository.save(event);
            return new IdempotentResponse(200, objectMapper.valueToTree(pieceMapper.toResponse(piece)));
        });
    }

    private PieceEntity findPiece(UUID pieceId) {
        return pieceRepository.findById(pieceId).orElseThrow(() -> pieceNotFound(pieceId));
    }

    private PieceEventEntity createEvent(PieceEntity piece, PieceStage stage, Instant occurredAt,
            String note, UUID actorId) {
        PieceEventEntity event = new PieceEventEntity();
        event.setPiece(piece);
        event.setStage(stage);
        event.setOccurredAt(occurredAt);
        event.setNote(note);
        event.setActor(userRepository.getReferenceById(actorId));
        return event;
    }

    private String allocatePieceCode() {
        Long sequence = jdbcTemplate.queryForObject(
                "insert into code_sequences (id, scope, sequence_year, next_value) "
                        + "values (?, 'PIECE', 0, 2) "
                        + "on conflict (scope, sequence_year) do update "
                        + "set next_value = code_sequences.next_value + 1 "
                        + "returning next_value - 1",
                Long.class, UUID.randomUUID());
        return formatPieceCode(sequence);
    }

    private Instant nextEventTime(UUID pieceId) {
        Instant now = clock.instant();
        return pieceEventRepository.findFirstByPiece_IdOrderByOccurredAtDescIdDesc(pieceId)
                .map(event -> {
                    Instant earliestNext = event.getOccurredAt().plusMillis(1);
                    return now.isAfter(earliestNext) ? now : earliestNext;
                })
                .orElse(now);
    }

    private static String formatPieceCode(Long sequence) {
        if (sequence == null || sequence < 1) {
            throw new IllegalStateException("Piece code sequence returned an invalid value");
        }
        return String.format(Locale.ROOT, "LU-%04d", sequence);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException pieceNotFound(UUID pieceId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PIECE_NOT_FOUND",
                "A peça " + pieceId + " não foi encontrada.");
    }
}
