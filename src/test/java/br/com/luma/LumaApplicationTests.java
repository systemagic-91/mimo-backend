package br.com.luma;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LumaApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
}