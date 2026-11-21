package br.com.luma.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import ch.qos.logback.classic.Level;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorLogTests {

    @Test
    void logsApiErrorsByStatusWithFieldNamesOnly(CapturedOutput output) throws Exception {
        try (AutoCloseable app = LogLevels.set("br.com.luma", Level.DEBUG)) {
            ApiErrorLog.log(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "/api/v1/sellers", List.of("phone", "name"));
            ApiErrorLog.log(HttpStatus.CONFLICT, "PIECE_NOT_AVAILABLE", "/api/v1/consignments", null);
            ApiErrorLog.log(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "/api/v1/pieces", null);
            ApiErrorLog.log(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "/api/v1/pieces", null);
        }

        assertThat(output).contains("INFO", "API error status=400 code=VALIDATION_ERROR path=/api/v1/sellers fields=[name, phone]");
        assertThat(output).contains("WARN", "API error status=409 code=PIECE_NOT_AVAILABLE path=/api/v1/consignments");
        // O 500 é logado com a stack trace pelo ApiExceptionHandler, não aqui.
        assertThat(output).doesNotContain("status=500");
        assertThat(output).contains("DEBUG", "Request blocked until password change path=/api/v1/pieces");
    }
}
