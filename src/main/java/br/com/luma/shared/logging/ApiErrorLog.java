package br.com.luma.shared.logging;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Log dos erros devolvidos pela API, num ponto só (usado pelo ApiExceptionHandler e pelo ProblemDetailWriter).
 * Só código, caminho e nomes de campo: nunca os valores enviados. requestId, traceId e userId vêm do MDC.
 */
public final class ApiErrorLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorLog.class);
    private static final Set<HttpStatus> WARN_STATUSES = Set.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
            HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    private ApiErrorLog() {
    }

    public static void log(HttpStatus status, String code, String path, Collection<String> fieldNames) {
        List<String> fields = fieldNames == null ? List.of() : fieldNames.stream().sorted().toList();
        if ("PASSWORD_CHANGE_REQUIRED".equals(code)) {
            LOGGER.debug("Request blocked until password change path={}", path);
        } else if (status.is5xxServerError()) {
            // O 500 é logado com a stack trace por quem tem a exceção (ApiExceptionHandler).
            return;
        } else if (WARN_STATUSES.contains(status)) {
            LOGGER.warn("API error status={} code={} path={}", status.value(), code, path);
        } else if (fields.isEmpty()) {
            LOGGER.info("API error status={} code={} path={}", status.value(), code, path);
        } else {
            LOGGER.info("API error status={} code={} path={} fields={}", status.value(), code, path, fields);
        }
    }
}
