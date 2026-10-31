package br.com.luma;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import br.com.luma.auth.dto.AuthResponse;
import br.com.luma.auth.dto.SessionResponse;
import br.com.luma.auth.exception.AuthException;
import br.com.luma.auth.service.AuthApplicationService;
import br.com.luma.auth.service.AuthApplicationService.IssuedSession;
import br.com.luma.dashboard.dto.DashboardResponse;
import br.com.luma.dashboard.service.DashboardApplicationService;
import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.service.ReportApplicationService;
import br.com.luma.shared.Role;
import br.com.luma.shared.dto.UserResponse;
import br.com.luma.upload.service.PhotoApplicationService;
import br.com.luma.upload.service.PhotoContent;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
class LumaApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PhotoApplicationService photoService;

    @MockitoBean
    private DashboardApplicationService dashboardService;

    @MockitoBean
    private ReportApplicationService reportService;

    @MockitoBean
    private AuthApplicationService authService;

    private static final AuthResponse AUTH_RESPONSE = new AuthResponse(
            new UserResponse(UUID.randomUUID(), "Dona", "dona@loja.com", Role.OWNER, true, false, null, null),
            new SessionResponse(java.time.Instant.parse("2026-10-31T12:00:00Z"),
                    java.time.Instant.parse("2026-10-31T12:15:00Z")),
            "access-token");

    @Test
    void loginPutsTheRefreshTokenOnlyInAnHttpOnlyCookie() throws Exception {
        when(authService.login(any(), eq("198.51.100.7")))
                .thenReturn(new IssuedSession(AUTH_RESPONSE, "refresh-secret"));

        mockMvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", "198.51.100.7, 10.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dona@loja.com\",\"password\":\"Senha-Forte-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("refresh-secret"))))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("luma_refresh=refresh-secret; Path=/api/v1/auth; Max-Age=2592000;"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))));
    }

    @Test
    void refreshReadsTheCookieAndRotatesIt() throws Exception {
        when(authService.refresh("old-refresh")).thenReturn(new IssuedSession(AUTH_RESPONSE, "new-refresh"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("Origin", "http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("luma_refresh", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.startsWith("luma_refresh=new-refresh;")));
    }

    @Test
    void refreshWithoutCookieOrWithInvalidTokenClearsTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith("luma_refresh=; Path=/api/v1/auth; Max-Age=0;")));

        when(authService.refresh("revoked")).thenThrow(new AuthException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "x"));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("luma_refresh", "revoked")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void refreshAndLogoutRejectUnknownOrigins() throws Exception {
        for (String path : java.util.List.of("/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            // Origem estrangeira: o CORS barra antes do controller.
            mockMvc.perform(post(path).header("Origin", "https://evil.example")
                    .cookie(new jakarta.servlet.http.Cookie("luma_refresh", "token")))
                    .andExpect(status().isForbidden());
            // Pedido que parece da mesma origem (fora do CORS): o OriginGuard confere a lista.
            mockMvc.perform(post(path).header("Origin", "https://evil.example")
                    .with(request -> {
                        request.setScheme("https");
                        request.setServerName("evil.example");
                        request.setServerPort(443);
                        return request;
                    })
                    .cookie(new jakarta.servlet.http.Cookie("luma_refresh", "token")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
        }
        org.mockito.Mockito.verifyNoInteractions(authService);
    }

    @Test
    void logoutWorksWithAnExpiredAccessTokenAndClearsTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header("Origin", "http://localhost:5173")
                .header("Authorization", "Bearer expired.or.invalid")
                .cookie(new jakarta.servlet.http.Cookie("luma_refresh", "session-token")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith("luma_refresh=; Path=/api/v1/auth; Max-Age=0;")));
        org.mockito.Mockito.verify(authService).logout("session-token");

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void protectedRoutesStillRejectInvalidBearerTokens() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer expired.or.invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAreOnlyForOwners() throws Exception {
        for (String path : java.util.List.of("/actuator/metrics", "/actuator/prometheus")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).with(role("STAFF"))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/actuator/metrics").with(role("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mockMvc.perform(get("/actuator/prometheus").with(role("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
    }

    @Test
    void everyResponseCarriesATraceId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void staffCannotOpenDashboardOrReports() throws Exception {
        for (String path : java.util.List.of("/api/v1/dashboard", "/api/v1/reports/overdue",
                "/api/v1/reports/stale-pieces", "/api/v1/reports/turnover?from=2026-10-01&to=2026-10-31")) {
            mockMvc.perform(get(path).with(role("STAFF")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void ownerOpensDashboardAndReports() throws Exception {
        when(dashboardService.get()).thenReturn(new DashboardResponse(
                new DashboardResponse.Kpis(0, 0, null, 0, 0, 0), java.util.Map.of(), java.util.List.of(),
                java.util.List.of()));
        when(reportService.overdue()).thenReturn(new OverdueReportResponse(java.util.List.of()));

        mockMvc.perform(get("/api/v1/dashboard").with(role("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.avgTurnoverDays").isEmpty());
        mockMvc.perform(get("/api/v1/reports/overdue").with(role("OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void turnoverReportRequiresValidDates() throws Exception {
        mockMvc.perform(get("/api/v1/reports/turnover?from=2026-10-01").with(role("OWNER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/turnover?from=01/10/2026&to=2026-10-31").with(role("OWNER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffCannotSendOrChangePieceCosts() throws Exception {
        mockMvc.perform(post("/api/v1/pieces/receive").with(role("STAFF")).header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Anel\",\"valueCents\":1000,\"stage\":\"FABRICA\",\"factoryCostCents\":300}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/pieces/" + UUID.randomUUID() + "/costs").with(role("STAFF"))
                .header("Idempotency-Key", "k2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bathCostCents\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void negativeCostsAreRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/pieces/receive").with(role("OWNER")).header("Idempotency-Key", "k3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Anel\",\"valueCents\":1000,\"stage\":\"FABRICA\",\"factoryCostCents\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void staffCannotManageUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users").with(role("STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/users/" + UUID.randomUUID() + "/reset-password").with(role("STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"temporaryPassword\":\"Temporaria-Senha-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingPasswordChangeOnlyAllowsTheChangeItself() throws Exception {
        for (String path : java.util.List.of("/api/v1/dashboard", "/api/v1/sellers", "/api/v1/users",
                "/actuator/metrics")) {
            mockMvc.perform(get(path).with(mustChangePassword()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        }
        // As rotas liberadas passam do filtro: o 400 vem da validação do corpo, não do bloqueio.
        mockMvc.perform(post("/api/v1/auth/change-password").with(mustChangePassword())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/logout").with(mustChangePassword()))
                .andExpect(status().isNoContent());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor mustChangePassword() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject(UUID.randomUUID().toString()).claim("mustChangePassword", true))
                .authorities(new SimpleGrantedAuthority("ROLE_OWNER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void contextStartsWithReplaceableTestDatabase() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", Integer.class))
                .isZero();
    }

    @Test
    void healthIsPublicWithoutExposingDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocumentIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Mimo Luma API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
                .andExpect(jsonPath("$.components.schemas.PaginationMetadata").exists())
                .andExpect(jsonPath("$.components.parameters.IdempotencyKey.name").value("Idempotency-Key"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['400']").exists());
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

        @Test
        void anonymousErrorsUseProblemDetailAndPropagateRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/pieces").header("X-Request-Id", "request-test-001"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("https://api.luma.com.br/problems/unauthenticated"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.requestId").value("request-test-001"))
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                result.getResponse().getContentType()).startsWith("application/problem+json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("X-Request-Id", "request-test-001"));
        }

    @Test
    void requestIdIsGeneratedWhenClientDoesNotProvideOne() throws Exception {
        mockMvc.perform(get("/api/v1/pieces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Request-Id", org.hamcrest.Matchers.startsWith("req_")));
    }

    @Test
    void missingPublicResourceReturnsProblemDetailWithRequestId() throws Exception {
        mockMvc.perform(get("/swagger-ui/does-not-exist.js").header("X-Request-Id", "missing-resource-003"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("missing-resource-003"));
    }

        @Test
        void validationErrorsExposeFieldMessagesWithoutEchoingValues() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Request-Id", "validation-test-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.requestId").value("validation-test-002"))
            .andExpect(jsonPath("$.fields.email").isArray())
            .andExpect(jsonPath("$.fields.password").isArray())
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                result.getResponse().getContentAsString()).doesNotContain("not-an-email"));
        }

    @Test
    void photosArePublicAndCachedForever() throws Exception {
        UUID photoId = UUID.randomUUID();
        when(photoService.find(photoId)).thenReturn(new PhotoContent("image/webp", new byte[] { 1, 2, 3 }));

        mockMvc.perform(get("/api/v1/photos/{photoId}", photoId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(new byte[] { 1, 2, 3 }))
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Pragma"));
    }

    @Test
    void photoUploadRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/v1/uploads/piece-photo")
                .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] { 1 }))
                .header("Idempotency-Key", "upload-1"))
                .andExpect(status().isUnauthorized());
    }
}
