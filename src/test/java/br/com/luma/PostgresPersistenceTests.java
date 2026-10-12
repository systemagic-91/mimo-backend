package br.com.luma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.entity.IdempotencyKeyEntity;
import br.com.luma.auth.entity.RefreshTokenEntity;
import br.com.luma.auth.repository.IdempotencyKeyRepository;
import br.com.luma.auth.repository.RefreshTokenRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.consignment.repository.ConsignmentItemRepository;
import br.com.luma.consignment.repository.ConsignmentRepository;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.piece.mapper.PieceMapper;
import br.com.luma.piece.repository.PieceEventRepository;
import br.com.luma.piece.repository.PieceRepository;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.mapper.SettingsMapper;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.seller.repository.SellerRepository;
import br.com.luma.shared.ConsignmentItemStatus;
import br.com.luma.shared.ConsignmentStatus;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.Role;
import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.entity.CodeSequenceEntity;
import br.com.luma.shared.repository.AuditLogRepository;
import br.com.luma.shared.repository.CodeSequenceRepository;

@SpringBootTest
@ActiveProfiles("test")
class PostgresPersistenceTests {

    private static final String externalDatabaseUrl = System.getenv("POSTGRES_TEST_URL");
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startPostgresWhenNoExternalDatabaseIsConfigured() {
        if (externalDatabaseUrl != null && !externalDatabaseUrl.isBlank()) {
            return;
        }

        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Set POSTGRES_TEST_URL when Docker is unavailable to Testcontainers");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgresContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void validateMigratedPostgresSchema(DynamicPropertyRegistry registry) {
        if (externalDatabaseUrl != null && !externalDatabaseUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalDatabaseUrl);
            registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        } else {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private PieceRepository pieceRepository;

    @Autowired
    private PieceEventRepository pieceEventRepository;

    @Autowired
    private ConsignmentRepository consignmentRepository;

    @Autowired
    private ConsignmentItemRepository consignmentItemRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private SettingsRepository settingsRepository;

    @Autowired
    private CodeSequenceRepository codeSequenceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PieceMapper pieceMapper;

    @Autowired
    private SettingsMapper settingsMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void persistsDomainRelationsSnapshotsAndGeneratedResponseWithoutSecrets() {
        UserEntity user = new UserEntity();
        user.setName("Owner");
        user.setEmail("owner@example.com");
        user.setPasswordHash("hashed-password");
        user.setRole(Role.OWNER);
        user = userRepository.saveAndFlush(user);

        SellerEntity seller = new SellerEntity();
        seller.setName("Ana Ribeiro");
        seller.setPhone("11999990000");
        seller.setCommissionRate(30);
        seller.setStatus(SellerStatus.ATIVA);
        seller = sellerRepository.saveAndFlush(seller);

        PieceEntity piece = new PieceEntity();
        piece.setCode("LU-0001");
        piece.setType("Brinco");
        piece.setValueCents(8490);
        piece.setStatus(PieceStage.CONSIGNACAO);
        piece.setCurrentLocation("Ana Ribeiro");
        piece = pieceRepository.saveAndFlush(piece);

        PieceEventEntity event = new PieceEventEntity();
        event.setPiece(piece);
        event.setStage(PieceStage.CONSIGNACAO);
        event.setOccurredAt(Instant.parse("2026-10-12T12:00:00Z"));
        event.setActor(user);
        pieceEventRepository.saveAndFlush(event);

        ConsignmentEntity consignment = new ConsignmentEntity();
        consignment.setCode("C-2026-001");
        consignment.setSeller(seller);
        consignment.setDeliveredAt(Instant.parse("2026-10-12T12:00:00Z"));
        consignment.setDueAt(Instant.parse("2026-11-11T12:00:00Z"));
        consignment.setStatus(ConsignmentStatus.ABERTA);
        consignment.setCreatedBy(user);
        consignment = consignmentRepository.saveAndFlush(consignment);

        ConsignmentItemEntity item = new ConsignmentItemEntity();
        item.setConsignment(consignment);
        item.setPiece(piece);
        item.setCodeSnapshot(piece.getCode());
        item.setTypeSnapshot(piece.getType());
        item.setValueCentsSnapshot(piece.getValueCents());
        item.setCommissionRateSnapshot(seller.getCommissionRate());
        item.setStatus(ConsignmentItemStatus.COM_VENDEDORA);
        consignmentItemRepository.saveAndFlush(item);

        assertThat(pieceEventRepository.findAllByPiece_IdOrderByOccurredAtAscIdAsc(piece.getId()))
                .extracting(PieceEventEntity::getActor)
                .containsExactly(user);
        assertThat(consignmentItemRepository.findAllByPiece_IdOrderByConsignment_DeliveredAtDesc(piece.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getValueCentsSnapshot()).isEqualTo(8490);
                    assertThat(saved.getCommissionRateSnapshot()).isEqualTo(30);
                });
        assertThat(pieceMapper.toResponse(piece).valueCents()).isEqualTo(8490);
        assertThat(pieceMapper.toResponse(piece).getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("passwordHash", "events");
    }

    @Test
    void partialUniqueIndexAllowsOnlyOneActiveConsignmentItemPerPiece() {
        UserEntity user = new UserEntity();
        user.setName("Owner");
        user.setEmail("unique-owner@example.com");
        user.setPasswordHash("hashed-password");
        user.setRole(Role.OWNER);
        user = userRepository.saveAndFlush(user);

        SellerEntity seller = new SellerEntity();
        seller.setName("Seller");
        seller.setCommissionRate(25);
        seller.setStatus(SellerStatus.ATIVA);
        seller = sellerRepository.saveAndFlush(seller);

        PieceEntity piece = new PieceEntity();
        piece.setCode("LU-0002");
        piece.setType("Anel");
        piece.setValueCents(5000);
        piece.setStatus(PieceStage.CONSIGNACAO);
        piece.setCurrentLocation("Seller");
        piece = pieceRepository.saveAndFlush(piece);
        PieceEntity activePiece = piece;

        ConsignmentEntity first = createConsignment(user, seller, "C-2026-002");
        ConsignmentEntity second = createConsignment(user, seller, "C-2026-003");
        saveActiveItem(first, activePiece);

        assertThatThrownBy(() -> saveActiveItem(second, activePiece))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

            @Test
            void databaseRejectsDuplicatePieceCodesAndNonPositiveValues() {
            PieceEntity piece = new PieceEntity();
            piece.setCode("LU-" + UUID.randomUUID().toString().substring(0, 8));
            piece.setType("Anel");
            piece.setValueCents(5000);
            piece.setStatus(PieceStage.ETIQUETADA);
            piece.setCurrentLocation("Estoque");
            piece = pieceRepository.saveAndFlush(piece);

            PieceEntity duplicate = new PieceEntity();
            duplicate.setCode(piece.getCode());
            duplicate.setType("Colar");
            duplicate.setValueCents(5000);
            duplicate.setStatus(PieceStage.ETIQUETADA);
            duplicate.setCurrentLocation("Estoque");
            PieceEntity duplicateCode = duplicate;
            assertThatThrownBy(() -> pieceRepository.saveAndFlush(duplicateCode))
                .isInstanceOf(DataIntegrityViolationException.class);

            PieceEntity invalidValue = new PieceEntity();
            invalidValue.setCode("LU-" + UUID.randomUUID().toString().substring(0, 8));
            invalidValue.setType("Brinco");
            invalidValue.setValueCents(0);
            invalidValue.setStatus(PieceStage.ETIQUETADA);
            invalidValue.setCurrentLocation("Estoque");
            assertThatThrownBy(() -> pieceRepository.saveAndFlush(invalidValue))
                .isInstanceOf(DataIntegrityViolationException.class);
            }

            @Test
            void databaseRejectsEventsForUnknownPieces() {
            assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO piece_events (id, piece_id, stage, occurred_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), PieceStage.FABRICA.name(), Instant.now()))
                .isInstanceOf(DataAccessException.class);
            }

            @Test
            @Transactional
            void pieceEventsCannotBeUpdatedOrDeleted() {
            PieceEntity piece = new PieceEntity();
            piece.setCode("LU-" + UUID.randomUUID().toString().substring(0, 8));
            piece.setType("Brinco");
            piece.setValueCents(2000);
            piece.setStatus(PieceStage.FABRICA);
            piece.setCurrentLocation("Fabrica");
            piece = pieceRepository.saveAndFlush(piece);

            PieceEventEntity event = new PieceEventEntity();
            event.setPiece(piece);
            event.setStage(PieceStage.FABRICA);
            event.setOccurredAt(Instant.now());
            event = pieceEventRepository.saveAndFlush(event);
            UUID eventId = event.getId();

            assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE piece_events SET note = ? WHERE id = ?", "mutation", eventId))
                .isInstanceOf(DataAccessException.class);
            }

            @Test
            @Transactional
            void persistsAuthenticationSettingsSequenceAuditAndIdempotencyEntities() throws Exception {
            Instant now = Instant.parse("2026-10-12T12:00:00Z");
            UserEntity user = new UserEntity();
            user.setName("Infrastructure owner");
            user.setEmail("infrastructure@example.com");
            user.setPasswordHash("hashed-password");
            user.setRole(Role.OWNER);
            user = userRepository.saveAndFlush(user);

            RefreshTokenEntity previous = new RefreshTokenEntity();
            previous.setUser(user);
            previous.setTokenHash("previous-hash");
            previous.setCreatedAt(now);
            previous.setExpiresAt(now.plusSeconds(3600));
            previous = refreshTokenRepository.saveAndFlush(previous);

            RefreshTokenEntity current = new RefreshTokenEntity();
            current.setUser(user);
            current.setTokenHash("current-hash");
            current.setCreatedAt(now);
            current.setExpiresAt(now.plusSeconds(7200));
            current = refreshTokenRepository.saveAndFlush(current);
            previous.setReplacedBy(current);
            refreshTokenRepository.saveAndFlush(previous);

            assertThat(refreshTokenRepository.findByTokenHash("previous-hash"))
                .get()
                .extracting(RefreshTokenEntity::getReplacedBy)
                .isEqualTo(current);

            SettingsEntity settings = new SettingsEntity();
            settings.setCommissionMin(25);
            settings.setCommissionMax(40);
            settings.setCommissionStep(5);
            settings.setDefaultCommissionRate(30);
            settings.setPaymentTerms(List.of(30, 60, 90));
            settings.setDefaultConsignmentDays(30);
            settings.setTimezone("America/Sao_Paulo");
            settings.setUpdatedAt(now);
            settings.setUpdatedBy(user);
            settings = settingsRepository.saveAndFlush(settings);

            assertThat(settingsMapper.toResponse(settings).updatedById()).isEqualTo(user.getId());
            assertThat(settingsRepository.findFirstBySingletonKeyTrue()).get()
                .extracting(SettingsEntity::getPaymentTerms)
                .isEqualTo(List.of(30, 60, 90));

            CodeSequenceEntity sequence = new CodeSequenceEntity();
            sequence.setScope("PIECE");
            sequence.setSequenceYear((short) 0);
            sequence.setNextValue(2);
            codeSequenceRepository.saveAndFlush(sequence);
            assertThat(codeSequenceRepository.findForUpdate("PIECE", (short) 0)).get()
                .extracting(CodeSequenceEntity::getNextValue)
                .isEqualTo(2L);

            UUID entityId = UUID.randomUUID();
            AuditLogEntity auditLog = new AuditLogEntity();
            auditLog.setActor(user);
            auditLog.setAction("PIECE_UPDATED");
            auditLog.setEntityType("PIECE");
            auditLog.setEntityId(entityId);
            auditLog.setBeforeState(Map.of("valueCents", 5000));
            auditLog.setAfterState(Map.of("valueCents", 6000));
            auditLog.setOccurredAt(now);
            auditLogRepository.saveAndFlush(auditLog);
            assertThat(auditLogRepository.findAll()).singleElement()
                .satisfies(saved -> assertThat(saved.getAfterState()).containsEntry("valueCents", 6000));

            IdempotencyKeyEntity idempotency = new IdempotencyKeyEntity();
            idempotency.setUser(user);
            idempotency.setIdempotencyKey("request-1");
            idempotency.setFingerprint("payload-sha256");
            idempotency.setResponseStatus((short) 201);
            idempotency.setResponseBody(objectMapper.readTree("{\"created\":true}"));
            idempotency.setCreatedAt(now);
            idempotency.setExpiresAt(now.plusSeconds(86400));
            idempotencyKeyRepository.saveAndFlush(idempotency);
            assertThat(idempotencyKeyRepository.findByUserIdAndIdempotencyKey(user.getId(), "request-1"))
                .get()
                .extracting(IdempotencyKeyEntity::getResponseStatus)
                .isEqualTo((short) 201);
            }

    private ConsignmentEntity createConsignment(UserEntity user, SellerEntity seller, String code) {
        ConsignmentEntity consignment = new ConsignmentEntity();
        consignment.setCode(code);
        consignment.setSeller(seller);
        consignment.setDeliveredAt(Instant.parse("2026-10-12T12:00:00Z"));
        consignment.setDueAt(Instant.parse("2026-11-11T12:00:00Z"));
        consignment.setStatus(ConsignmentStatus.ABERTA);
        consignment.setCreatedBy(user);
        return consignmentRepository.saveAndFlush(consignment);
    }

    private void saveActiveItem(ConsignmentEntity consignment, PieceEntity piece) {
        ConsignmentItemEntity item = new ConsignmentItemEntity();
        item.setConsignment(consignment);
        item.setPiece(piece);
        item.setCodeSnapshot(piece.getCode());
        item.setTypeSnapshot(piece.getType());
        item.setValueCentsSnapshot(piece.getValueCents());
        item.setCommissionRateSnapshot(25);
        item.setStatus(ConsignmentItemStatus.COM_VENDEDORA);
        consignmentItemRepository.saveAndFlush(item);
    }
}