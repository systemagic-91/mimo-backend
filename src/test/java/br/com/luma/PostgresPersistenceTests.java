package br.com.luma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.entity.IdempotencyKeyEntity;
import br.com.luma.auth.entity.RefreshTokenEntity;
import br.com.luma.auth.service.RefreshTokenHasher;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.exception.ApiException;
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
import br.com.luma.seller.service.SellerApplicationService;
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
@AutoConfigureMockMvc
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
    private SellerApplicationService sellerApplicationService;

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

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Autowired
        private JwtEncoder jwtEncoder;

        @Autowired
        private RefreshTokenHasher refreshTokenHasher;

        @Autowired
        private IdempotencyService idempotencyService;

        @Value("${app.security.jwt.issuer}")
        private String jwtIssuer;

        @Test
        void loginAndMeReturnOnlyPublicUserFields() throws Exception {
        UserEntity user = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");

        MvcResult loginResult = postJson("/api/v1/auth/login", Map.of(
            "email", user.getEmail(), "password", "CorrectHorseBattery77!"), null);
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        var login = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        assertThat(login.path("user").path("role").asText()).isEqualTo("owner");
        assertThat(login.path("accessToken").asText()).isNotBlank();
        assertThat(login.path("refreshToken").asText()).isNotBlank();
        assertThat(login.path("user").has("passwordHash")).isFalse();
        assertThat(login.path("user").has("password")).isFalse();

        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + login.path("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(user.getEmail()))
            .andExpect(jsonPath("$.user.role").value("owner"))
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.session.loggedAt").exists())
            .andExpect(jsonPath("$.session.expiresAt").exists());
        }

        @Test
        void invalidCredentialsAreGenericAndInactiveUsersAreRejected() throws Exception {
        UserEntity inactive = createAuthUser(Role.STAFF, false, "CorrectHorseBattery77!");

        MvcResult unknownEmail = postJson("/api/v1/auth/login", Map.of(
            "email", "missing-" + UUID.randomUUID() + "@example.com", "password", "WrongPassword123!"), null);
        MvcResult wrongPassword = postJson("/api/v1/auth/login", Map.of(
            "email", inactive.getEmail(), "password", "WrongPassword123!"), null);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(unknownEmail.getResponse().getContentAsString()).path("detail").asText())
            .isEqualTo(objectMapper.readTree(wrongPassword.getResponse().getContentAsString()).path("detail").asText());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "email", inactive.getEmail(), "password", "CorrectHorseBattery77!"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("USER_INACTIVE"));
        }

        @Test
        void refreshRotatesTokensAndReplayRevokesAllActiveSessions() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        var login = login(user.getEmail(), "CorrectHorseBattery77!");
        String originalRefreshToken = login.path("refreshToken").asText();

        var rotated = refresh(originalRefreshToken);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        var rotatedBody = objectMapper.readTree(rotated.getResponse().getContentAsString());
        String rotatedRefreshToken = rotatedBody.path("refreshToken").asText();
        assertThat(rotatedRefreshToken).isNotEqualTo(originalRefreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", originalRefreshToken))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", rotatedRefreshToken))))
            .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.findByTokenHash(refreshTokenHasher.hash(rotatedRefreshToken)))
            .get()
            .extracting(RefreshTokenEntity::getRevokedAt)
            .isNotNull();
        }

    @Test
    void expiredRefreshTokenIsRevokedAndRejected() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        Instant now = Instant.now();
        String rawRefreshToken = "expired-" + UUID.randomUUID();

        RefreshTokenEntity expired = new RefreshTokenEntity();
        expired.setUser(user);
        expired.setTokenHash(refreshTokenHasher.hash(rawRefreshToken));
        expired.setCreatedAt(now.minus(2, ChronoUnit.DAYS));
        expired.setExpiresAt(now.minus(1, ChronoUnit.DAYS));
        refreshTokenRepository.saveAndFlush(expired);

        assertThat(refresh(rawRefreshToken).getResponse().getStatus()).isEqualTo(401);
        assertThat(refreshTokenRepository.findByTokenHash(refreshTokenHasher.hash(rawRefreshToken)))
                .get()
                .extracting(RefreshTokenEntity::getRevokedAt)
                .isNotNull();
    }

            @Test
            void sellerMutationsAreOwnerOnlyAndCreateReplaysIdempotently() throws Exception {
            UserEntity owner = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
            UserEntity staff = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
            String ownerToken = login(owner.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();
            String staffToken = login(staff.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();
            String key = "seller-create-" + UUID.randomUUID();
            Map<String, Object> request = Map.of("name", "Seller " + UUID.randomUUID(),
                "phone", "11999990000", "commissionRate", 30);

            MvcResult first = mockMvc.perform(post("/api/v1/sellers")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
            String sellerId = objectMapper.readTree(first.getResponse().getContentAsString()).path("id").asText();

            mockMvc.perform(post("/api/v1/sellers")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sellerId));

            mockMvc.perform(patch("/api/v1/sellers/{sellerId}", sellerId)
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "deactivate-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("status", "inativa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inativa"));

            mockMvc.perform(patch("/api/v1/sellers/{sellerId}", sellerId)
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "reactivate-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("status", "ativa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ativa"));

            mockMvc.perform(post("/api/v1/sellers")
                    .header("Authorization", "Bearer " + staffToken)
                    .header("Idempotency-Key", "staff-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            mockMvc.perform(get("/api/v1/sellers")
                    .header("Authorization", "Bearer " + staffToken)
                    .param("q", request.get("name").toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].seller.id").value(sellerId));
            }

            @Test
            void sellerAndSettingsRejectCommissionAndTermValuesOutsideConfiguredRules() throws Exception {
            UserEntity owner = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
            String ownerToken = login(owner.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();

            mockMvc.perform(post("/api/v1/sellers")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "bad-seller-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of(
                        "name", "Invalid seller", "phone", "", "commissionRate", 32))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            mockMvc.perform(patch("/api/v1/settings")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "bad-settings-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of(
                        "defaultCommissionRate", 32,
                        "paymentTerms", List.of(30, 45),
                        "defaultConsignmentDays", 30,
                        "timezone", "America/Sao_Paulo"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            mockMvc.perform(patch("/api/v1/settings")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "bad-zone-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of(
                        "defaultCommissionRate", 35,
                        "paymentTerms", List.of(30, 90),
                        "defaultConsignmentDays", 30,
                        "timezone", "Not/A_Timezone"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.timezone").exists());
            }

    @Test
    void staleSellerUpdateFailsWithOptimisticLockConflict() {
        SellerEntity seller = new SellerEntity();
        seller.setName("Concurrent seller " + UUID.randomUUID());
        seller.setPhone("111");
        seller.setCommissionRate(30);
        seller.setStatus(SellerStatus.ATIVA);
        seller = sellerRepository.saveAndFlush(seller);
        UUID sellerId = seller.getId();

        SellerEntity staleCopy = sellerRepository.findById(sellerId).orElseThrow();
        SellerEntity currentCopy = sellerRepository.findById(sellerId).orElseThrow();
        currentCopy.setPhone("222");
        sellerRepository.saveAndFlush(currentCopy);

        staleCopy.setPhone("333");
        assertThatThrownBy(() -> sellerRepository.saveAndFlush(staleCopy))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void inactiveSellerIsRejectedByConsignmentPrecondition() {
        SellerEntity inactive = new SellerEntity();
        inactive.setName("Inactive seller " + UUID.randomUUID());
        inactive.setPhone("11999990000");
        inactive.setCommissionRate(30);
        inactive.setStatus(SellerStatus.INATIVA);
        inactive = sellerRepository.saveAndFlush(inactive);
        UUID inactiveSellerId = inactive.getId();

        assertThatThrownBy(() -> sellerApplicationService.requireActiveForConsignment(inactiveSellerId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getCode()).isEqualTo("SELLER_INACTIVE");
                });

        UserEntity owner = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
        SellerEntity inactiveSeller = sellerRepository.findById(inactiveSellerId).orElseThrow();
        assertThatThrownBy(() -> createConsignment(owner, inactiveSeller,
            "C-" + UUID.randomUUID().toString().substring(0, 8)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

            @Test
            void settingsCanBeUpdatedAndSellerCommissionUsesImmutableSnapshot() throws Exception {
            UserEntity owner = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
            String ownerToken = login(owner.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();

            mockMvc.perform(patch("/api/v1/settings")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("Idempotency-Key", "valid-settings-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of(
                        "defaultCommissionRate", 35,
                        "paymentTerms", List.of(30, 90),
                        "defaultConsignmentDays", 45,
                        "timezone", "America/Sao_Paulo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCommissionRate").value(35))
                .andExpect(jsonPath("$.paymentTerms[1]").value(90));

            SellerEntity seller = new SellerEntity();
            seller.setName("Snapshot seller " + UUID.randomUUID());
            seller.setPhone("11999990000");
            seller.setCommissionRate(30);
            seller.setStatus(SellerStatus.ATIVA);
            seller = sellerRepository.saveAndFlush(seller);
            UUID sellerId = seller.getId();

            ConsignmentEntity soldConsignment = new ConsignmentEntity();
            soldConsignment.setCode("C-" + UUID.randomUUID().toString().substring(0, 8));
            soldConsignment.setSeller(seller);
            soldConsignment.setDeliveredAt(Instant.parse("2026-01-01T03:00:00Z"));
            soldConsignment.setDueAt(Instant.parse("2026-01-31T03:00:00Z"));
            soldConsignment.setStatus(ConsignmentStatus.QUITADA);
            soldConsignment.setCreatedBy(owner);
            soldConsignment = consignmentRepository.saveAndFlush(soldConsignment);

            PieceEntity soldPiece = new PieceEntity();
            soldPiece.setCode("LU-" + UUID.randomUUID().toString().substring(0, 8));
            soldPiece.setType("Anel");
            soldPiece.setValueCents(10001);
            soldPiece.setStatus(PieceStage.VENDIDA);
            soldPiece.setCurrentLocation("Vendida");
            soldPiece = pieceRepository.saveAndFlush(soldPiece);

            ConsignmentItemEntity soldItem = new ConsignmentItemEntity();
            soldItem.setConsignment(soldConsignment);
            soldItem.setPiece(soldPiece);
            soldItem.setCodeSnapshot(soldPiece.getCode());
            soldItem.setTypeSnapshot(soldPiece.getType());
            soldItem.setValueCentsSnapshot(10001);
            soldItem.setCommissionRateSnapshot(30);
            soldItem.setStatus(ConsignmentItemStatus.VENDIDA);
            soldItem.setSoldAt(Instant.parse("2026-01-01T03:00:00Z"));
            consignmentItemRepository.saveAndFlush(soldItem);

            ConsignmentEntity openConsignment = new ConsignmentEntity();
            openConsignment.setCode("C-" + UUID.randomUUID().toString().substring(0, 8));
            openConsignment.setSeller(seller);
            openConsignment.setDeliveredAt(Instant.now().minusSeconds(3600));
            openConsignment.setDueAt(Instant.now().plusSeconds(86400));
            openConsignment.setStatus(ConsignmentStatus.ABERTA);
            openConsignment.setCreatedBy(owner);
            openConsignment = consignmentRepository.saveAndFlush(openConsignment);

            PieceEntity inHandPiece = new PieceEntity();
            inHandPiece.setCode("LU-" + UUID.randomUUID().toString().substring(0, 8));
            inHandPiece.setType("Colar");
            inHandPiece.setValueCents(8490);
            inHandPiece.setStatus(PieceStage.CONSIGNACAO);
            inHandPiece.setCurrentLocation(seller.getName());
            inHandPiece = pieceRepository.saveAndFlush(inHandPiece);

            ConsignmentItemEntity inHandItem = new ConsignmentItemEntity();
            inHandItem.setConsignment(openConsignment);
            inHandItem.setPiece(inHandPiece);
            inHandItem.setCodeSnapshot(inHandPiece.getCode());
            inHandItem.setTypeSnapshot(inHandPiece.getType());
            inHandItem.setValueCentsSnapshot(8490);
            inHandItem.setCommissionRateSnapshot(35);
            inHandItem.setStatus(ConsignmentItemStatus.COM_VENDEDORA);
            consignmentItemRepository.saveAndFlush(inHandItem);

            SettingsEntity settings = settingsRepository.findFirstBySingletonKeyTrue().orElseThrow();
            settings.setDefaultCommissionRate(40);
            settingsRepository.saveAndFlush(settings);

            mockMvc.perform(get("/api/v1/sellers/{sellerId}/commission", sellerId)
                    .header("Authorization", "Bearer " + ownerToken)
                    .param("from", "2026-01-01")
                    .param("to", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldCount").value(1))
                .andExpect(jsonPath("$.soldValueCents").value(10001))
                .andExpect(jsonPath("$.commissionCents").value(3000));

            mockMvc.perform(get("/api/v1/sellers/{sellerId}/stats", sellerId)
                    .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inHand").value(1))
                .andExpect(jsonPath("$.inHandValueCents").value(8490))
                .andExpect(jsonPath("$.totalSoldCount").value(1))
                .andExpect(jsonPath("$.totalCommissionCents").value(3000));
            }

        @Test
        void logoutRevokesRefreshToken() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        var login = login(user.getEmail(), "CorrectHorseBattery77!");
        String accessToken = login.path("accessToken").asText();
        String refreshToken = login.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.findByTokenHash(refreshTokenHasher.hash(refreshToken)))
            .get()
            .extracting(RefreshTokenEntity::getRevokedAt)
            .isNotNull();
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void changingPasswordInvalidatesAccessAndRefreshTokensImmediately() throws Exception {
        UserEntity user = createAuthUser(Role.OWNER, true, "OldPasswordVeryStrong77!");
        var login = login(user.getEmail(), "OldPasswordVeryStrong77!");
        String accessToken = login.path("accessToken").asText();
        String refreshToken = login.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "currentPassword", "OldPasswordVeryStrong77!",
                    "newPassword", "NewPasswordVeryStrong88!"))))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isUnauthorized());
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(user.getEmail(), "OldPasswordVeryStrong77!").path("code").asText())
            .isEqualTo("INVALID_CREDENTIALS");
        assertThat(login(user.getEmail(), "NewPasswordVeryStrong88!").path("accessToken").asText()).isNotBlank();
        }

        @Test
        void expiredAccessTokensAreRejectedAndSettingsAreOwnerOnly() throws Exception {
        UserEntity staff = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        UserEntity owner = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
        var staffLogin = login(staff.getEmail(), "CorrectHorseBattery77!");
        var ownerLogin = login(owner.getEmail(), "CorrectHorseBattery77!");

        mockMvc.perform(get("/api/v1/settings")
                .header("Authorization", "Bearer " + staffLogin.path("accessToken").asText()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/settings")
                .header("Authorization", "Bearer " + ownerLogin.path("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commissionMin").value(25));
        mockMvc.perform(get("/api/v1/pieces"))
            .andExpect(status().isUnauthorized());

        String expiredToken = createExpiredAccessToken(staff);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized());
        }

        @Test
        void loginIsRateLimitedByClientIp() throws Exception {
        String email = "missing-" + UUID.randomUUID() + "@example.com";
        for (int attempt = 0; attempt < 5; attempt++) {
            postJson("/api/v1/auth/login", Map.of("email", email, "password", "WrongPassword123!"), "198.51.100.77");
        }

        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("email", email, "password", "WrongPassword123!"))))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        }

        @Test
        void idempotentCommandReplaysResponseAndRejectsDifferentPayload() throws Exception {
        UserEntity user = createAuthUser(Role.OWNER, true, "CorrectHorseBattery77!");
        String key = "receive-" + UUID.randomUUID();
        var firstPayload = objectMapper.readTree("{\"pieceIds\":[\"a\"],\"valueCents\":8490}");
        var reorderedPayload = objectMapper.readTree("{\"valueCents\":8490,\"pieceIds\":[\"a\"]}");
        var conflictingPayload = objectMapper.readTree("{\"pieceIds\":[\"a\"],\"valueCents\":9000}");
        var responseBody = objectMapper.readTree("{\"pieceId\":\"piece-1\"}");
        AtomicInteger executions = new AtomicInteger();

        IdempotentResponse first = idempotencyService.execute(user.getId(), "pieces.receive", key,
            firstPayload, () -> {
                executions.incrementAndGet();
                return new IdempotentResponse(201, responseBody);
            });
        IdempotentResponse replay = idempotencyService.execute(user.getId(), "pieces.receive", key,
            reorderedPayload, () -> {
                executions.incrementAndGet();
                return new IdempotentResponse(201, responseBody);
            });

        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
        assertThatThrownBy(() -> idempotencyService.execute(user.getId(), "pieces.receive", key,
            conflictingPayload, () -> new IdempotentResponse(201, responseBody)))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            });
        assertThat(idempotencyKeyRepository.findByUserIdAndIdempotencyKey(user.getId(),
            "pieces.receive:" + key)).get()
            .extracting(IdempotencyKeyEntity::getResponseStatus)
            .isEqualTo((short) 201);
        }

        @Test
        void expiredIdempotencyKeyCanBeClaimedAgain() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        String key = "expired-" + UUID.randomUUID();
        var payload = objectMapper.readTree("{\"type\":\"Brinco\"}");
        AtomicInteger executions = new AtomicInteger();
        var action = (java.util.function.Supplier<IdempotentResponse>) () -> {
            int execution = executions.incrementAndGet();
            return new IdempotentResponse(201,
                objectMapper.valueToTree(Map.of("execution", execution)));
        };

        idempotencyService.execute(user.getId(), "pieces.receive", key, payload, action);
        jdbcTemplate.update("UPDATE idempotency_keys SET created_at = now() - interval '2 seconds', "
            + "expires_at = now() - interval '1 second' "
            + "WHERE user_id = ? AND idempotency_key = ?", user.getId(), "pieces.receive:" + key);
        IdempotentResponse second = idempotencyService.execute(
            user.getId(), "pieces.receive", key, payload, action);

        assertThat(executions).hasValue(2);
        assertThat(second.body().path("execution").asInt()).isEqualTo(2);
        }

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
    void receivesLabelsSearchesAndPreservesPieceHistory() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        String accessToken = login(user.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();
        long originalPieceCount = pieceRepository.count();
        String preview = objectMapper.readTree(mockMvc.perform(get("/api/v1/pieces/next-code")
                .header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString()).path("code").asText();

        MvcResult received = postPieceJson("/api/v1/pieces/receive",
                Map.of("type", "Brinco", "valueCents", 8490,
                        "stage", "BANHO_OURO", "note", "Lote de teste"),
                accessToken, "receive-" + UUID.randomUUID());
        assertThat(received.getResponse().getStatus()).isEqualTo(201);
        JsonNode receivedBody = objectMapper.readTree(received.getResponse().getContentAsString());
        JsonNode receivedPiece = receivedBody.path("piece");
        UUID pieceId = UUID.fromString(receivedPiece.path("id").asText());
        String code = receivedPiece.path("code").asText();
        assertThat(code).isEqualTo(preview);
        assertThat(receivedPiece.path("status").asText()).isEqualTo("BANHO_OURO");
        assertThat(receivedPiece.path("currentLocation").asText()).isEqualTo("No banho de ouro");
        assertThat(receivedBody.path("events").size()).isEqualTo(2);
        assertThat(receivedBody.path("events").get(0).path("stage").asText()).isEqualTo("FABRICA");
        assertThat(receivedBody.path("events").get(1).path("stage").asText()).isEqualTo("BANHO_OURO");
        assertThat(Instant.parse(receivedBody.path("events").get(0).path("occurredAt").asText()))
                .isBefore(Instant.parse(receivedBody.path("events").get(1).path("occurredAt").asText()));
        assertThat(receivedBody.path("events").get(0).path("actorUserId").asText()).isEqualTo(user.getId().toString());
        assertThat(pieceRepository.count()).isEqualTo(originalPieceCount + 1);

        MvcResult labeled = postPieceJson("/api/v1/pieces/label",
                Map.of("sourcePieceId", pieceId, "type", "Brinco", "valueCents", 8990,
                        "photoUrl", "https://storage.example/pieces/test.jpg", "note", "Dourado",
                        "backFromBath", true),
                accessToken, "label-" + UUID.randomUUID());
        assertThat(labeled.getResponse().getStatus()).isEqualTo(200);
        JsonNode labeledPiece = objectMapper.readTree(labeled.getResponse().getContentAsString());
        assertThat(labeledPiece.path("id").asText()).isEqualTo(pieceId.toString());
        assertThat(labeledPiece.path("code").asText()).isEqualTo(code);
        assertThat(labeledPiece.path("status").asText()).isEqualTo("ETIQUETADA");
        assertThat(labeledPiece.path("currentLocation").asText()).isEqualTo("Estoque");
        assertThat(labeledPiece.path("photoUrl").asText()).isEqualTo("https://storage.example/pieces/test.jpg");
        assertThat(pieceRepository.count()).isEqualTo(originalPieceCount + 1);

        mockMvc.perform(get("/api/v1/pieces/{pieceId}", pieceId)
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.valueCents").value(8990));
        mockMvc.perform(get("/api/v1/pieces/{pieceId}/events", pieceId)
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[2].stage").value("ETIQUETADA"))
                .andExpect(jsonPath("$[2].actorUserId").value(user.getId().toString()));
        mockMvc.perform(get("/api/v1/pieces")
                .header("Authorization", "Bearer " + accessToken)
                .param("q", code)
                .param("status", "ETIQUETADA")
                .param("page", "1")
                .param("pageSize", "10")
                .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(pieceId.toString()))
                .andExpect(jsonPath("$.pagination.page").value(1));
        mockMvc.perform(get("/api/v1/pieces")
                .header("Authorization", "Bearer " + accessToken)
                .param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void rejectsInvalidPieceStagesAndLabelingWithoutBathReturn() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        String accessToken = login(user.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();

        MvcResult invalidType = postPieceJson("/api/v1/pieces/receive",
                Map.of("type", "Tipo inexistente", "valueCents", 1000, "stage", "FABRICA"),
                accessToken, "invalid-type-" + UUID.randomUUID());
        assertThat(invalidType.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(invalidType.getResponse().getContentAsString()).path("fields").has("type"))
                .isTrue();

        MvcResult invalidReceive = postPieceJson("/api/v1/pieces/receive",
                Map.of("type", "Brinco", "valueCents", 1000, "stage", "ETIQUETADA"),
                accessToken, "invalid-receive-" + UUID.randomUUID());
        assertThat(invalidReceive.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(invalidReceive.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_INITIAL_STAGE");

        MvcResult factoryPiece = postPieceJson("/api/v1/pieces/receive",
                Map.of("type", "Brinco", "valueCents", 2000, "stage", "FABRICA"),
                accessToken, "factory-" + UUID.randomUUID());
        assertThat(factoryPiece.getResponse().getStatus()).isEqualTo(201);
        JsonNode factoryReceipt = objectMapper.readTree(factoryPiece.getResponse().getContentAsString());
        assertThat(factoryReceipt.path("piece").path("status").asText()).isEqualTo("FABRICA");
        assertThat(factoryReceipt.path("piece").path("currentLocation").asText()).isEqualTo("Fábrica");
        assertThat(factoryReceipt.path("events").size()).isEqualTo(1);
        assertThat(factoryReceipt.path("events").get(0).path("stage").asText()).isEqualTo("FABRICA");
        assertThat(factoryReceipt.path("events").get(0).path("actorUserId").asText()).isEqualTo(user.getId().toString());
        UUID factoryPieceId = UUID.fromString(factoryReceipt.path("piece").path("id").asText());
        MvcResult invalidLabel = postPieceJson("/api/v1/pieces/label",
                Map.of("sourcePieceId", factoryPieceId, "type", "Brinco", "valueCents", 2000,
                        "backFromBath", true),
                accessToken, "invalid-label-" + UUID.randomUUID());
        assertThat(invalidLabel.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(invalidLabel.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_PIECE_TRANSITION");

        MvcResult bathPiece = postPieceJson("/api/v1/pieces/receive",
                Map.of("type", "Colar", "valueCents", 3000, "stage", "BANHO_OURO"),
                accessToken, "bath-" + UUID.randomUUID());
        UUID bathPieceId = UUID.fromString(objectMapper.readTree(bathPiece.getResponse().getContentAsString())
                .path("piece").path("id").asText());
        MvcResult falseBathReturn = postPieceJson("/api/v1/pieces/label",
                Map.of("sourcePieceId", bathPieceId, "type", "Colar", "valueCents", 3000,
                        "backFromBath", false),
                accessToken, "false-bath-return-" + UUID.randomUUID());
        assertThat(falseBathReturn.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(falseBathReturn.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_PIECE_TRANSITION");
        assertThat(pieceRepository.findById(bathPieceId)).get()
                .extracting(PieceEntity::getStatus).isEqualTo(PieceStage.BANHO_OURO);
        assertThat(pieceEventRepository.findAllByPiece_IdOrderByOccurredAtAscIdAsc(bathPieceId))
                .extracting(PieceEventEntity::getStage)
                .containsExactly(PieceStage.FABRICA, PieceStage.BANHO_OURO);
    }

    @Test
    void issuesUniquePieceCodesForConcurrentReceipts() throws Exception {
        UserEntity user = createAuthUser(Role.STAFF, true, "CorrectHorseBattery77!");
        String accessToken = login(user.getEmail(), "CorrectHorseBattery77!").path("accessToken").asText();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> requests = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String idempotencyKey = "concurrent-receive-" + UUID.randomUUID();
                requests.add(() -> {
                    MvcResult result = postPieceJson("/api/v1/pieces/receive",
                            Map.of("type", "Pulseira", "valueCents", 1500, "stage", "FABRICA"),
                            accessToken, idempotencyKey);
                    assertThat(result.getResponse().getStatus()).isEqualTo(201);
                    return objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("piece").path("code").asText();
                });
            }
            List<Future<String>> results = executor.invokeAll(requests, 30, TimeUnit.SECONDS);
            List<String> codes = results.stream().map(result -> {
                try {
                    assertThat(result.isDone()).isTrue();
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError("Concurrent piece receipt failed", exception);
                }
            }).toList();
            assertThat(codes).hasSize(16).doesNotHaveDuplicates();
            assertThat(codes).allMatch(code -> code.matches("LU-[0-9]{4,}"));
        } finally {
            executor.shutdownNow();
        }
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

            SettingsEntity settings = settingsRepository.findFirstBySingletonKeyTrue().orElseThrow();
            settings.setCommissionMin(25);
            settings.setCommissionMax(40);
            settings.setCommissionStep(5);
            settings.setDefaultCommissionRate(30);
            settings.setPaymentTerms(List.of(30, 60, 90));
            settings.setDefaultConsignmentDays(30);
            settings.setTimezone("America/Sao_Paulo");
            settings.setUpdatedAt(now);
            settings.setUpdatedBy(user);

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
            assertThat(auditLogRepository.findAll())
                .filteredOn(saved -> entityId.equals(saved.getEntityId()))
                .singleElement()
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

    private UserEntity createAuthUser(Role role, boolean active, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setName("Auth test user");
        user.setEmail("auth-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setActive(active);
        return userRepository.saveAndFlush(user);
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = postJson("/api/v1/auth/login", Map.of("email", email, "password", password), null);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null);
    }

    private MvcResult postJson(String path, Object body, String clientIp) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (clientIp != null) {
            request.with(mockRequest -> {
                mockRequest.setRemoteAddr(clientIp);
                return mockRequest;
            });
        }
        return mockMvc.perform(request.content(objectMapper.writeValueAsBytes(body))).andReturn();
    }

    private MvcResult postPieceJson(String path, Object body, String accessToken, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
                .andReturn();
    }

    private String createExpiredAccessToken(UserEntity user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtIssuer)
                .subject(user.getId().toString())
                .issuedAt(now.minus(2, ChronoUnit.MINUTES))
                .expiresAt(now.minus(1, ChronoUnit.MINUTES))
                .claim("email", user.getEmail())
                .claim("roles", List.of("ROLE_" + user.getRole().name()))
                .claim("authVersion", user.getAuthVersion())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}