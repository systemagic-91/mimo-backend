package br.com.luma.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration
public class OpenApiConfiguration {

    private static final List<String> STANDARD_ERROR_CODES = List.of("400", "401", "403", "404", "409", "422", "429", "500");

    @Bean
    OpenAPI lumaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mimo Luma API")
                        .description("API REST para estoque, vendedoras e consignações.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSchemas("ProblemDetail", problemSchema())
                        .addSchemas("PaginationMetadata", paginationSchema())
                        .addSchemas("PageEnvelope", pageEnvelopeSchema())
                        .addParameters("IdempotencyKey", idempotencyParameter()));
    }

    @Bean
    OpenApiCustomizer commonProblemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas("ProblemDetail", problemSchema());
                        openApi.getComponents().addSchemas("PaginationMetadata", paginationSchema());
                        openApi.getComponents().addSchemas("PageEnvelope", pageEnvelopeSchema());
                        openApi.getComponents().addParameters("IdempotencyKey", idempotencyParameter());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    STANDARD_ERROR_CODES.forEach(status -> operation.getResponses().addApiResponse(status,
                            new ApiResponse()
                                    .description("Erro HTTP " + status)
                                    .content(new Content().addMediaType("application/problem+json",
                                            new io.swagger.v3.oas.models.media.MediaType()
                                                    .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))))))));
        };
    }

    private Schema<?> problemSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new Schema<>().type("integer"))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", new StringSchema())
                .addProperty("requestId", new StringSchema())
                .addProperty("fields", new ObjectSchema().additionalProperties(true));
    }

        private Schema<?> paginationSchema() {
                return new ObjectSchema()
                                .addProperty("page", new Schema<>().type("integer").minimum(new java.math.BigDecimal("1")))
                                .addProperty("pageSize", new Schema<>().type("integer").minimum(new java.math.BigDecimal("1"))
                                                .maximum(new java.math.BigDecimal("200")))
                                .addProperty("total", new Schema<>().type("integer").format("int64"))
                                .addProperty("totalPages", new Schema<>().type("integer"));
        }

        private Schema<?> pageEnvelopeSchema() {
                return new ObjectSchema()
                                .addProperty("items", new ArraySchema().items(new ObjectSchema()))
                                .addProperty("pagination", new Schema<>().$ref("#/components/schemas/PaginationMetadata"));
        }

        private Parameter idempotencyParameter() {
                return new Parameter()
                                .name("Idempotency-Key")
                                .in("header")
                                .required(true)
                                .description("Chave de retry, escopada por usuário e operação; até 160 caracteres.")
                                .schema(new StringSchema().maxLength(160));
        }
}