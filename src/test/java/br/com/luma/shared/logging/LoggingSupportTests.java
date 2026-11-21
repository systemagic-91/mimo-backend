package br.com.luma.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class LoggingSupportTests {

    private AutoCloseable app;
    private AutoCloseable access;

    @BeforeEach
    void enableLogs() {
        app = LogLevels.set("br.com.luma", Level.DEBUG);
        access = LogLevels.set(AccessLogFilter.LOGGER_NAME, Level.INFO);
    }

    @AfterEach
    void restoreLogs() throws Exception {
        app.close();
        access.close();
    }

    @Test
    void masksEmails() {
        assertThat(LogMasking.email("luma@email.com")).isEqualTo("l***@email.com");
        assertThat(LogMasking.email(" Ana@Loja.com.br ")).isEqualTo("A***@Loja.com.br");
        assertThat(LogMasking.email("sem-arroba")).isEqualTo("***");
        assertThat(LogMasking.email(null)).isEqualTo("***");
    }

    @Test
    void writesOneAccessLineWithoutTheQueryAndSkipsHealthChecks(CapturedOutput output) throws Exception {
        AccessLogFilter filter = new AccessLogFilter(1_000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pieces");
        request.setQueryString("q=texto-digitado");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpServletResponse.SC_OK);

        filter.doFilter(request, response, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(output).contains("HTTP request method=GET path=/api/v1/pieces status=200 durationMs=");
        assertThat(output).doesNotContain("texto-digitado").doesNotContain("/actuator/health");
    }

    @Test
    void marksSlowRequestsAsWarnings(CapturedOutput output) throws Exception {
        AccessLogFilter filter = new AccessLogFilter(0);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/consignments"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(output).contains("WARN", "path=/api/v1/consignments", "slow=true");
    }

    @Test
    void measuresNothingWhenTheAccessLogIsOff(CapturedOutput output) throws Exception {
        try (AutoCloseable off = LogLevels.set(AccessLogFilter.LOGGER_NAME, Level.OFF)) {
            new AccessLogFilter(0).doFilter(new MockHttpServletRequest("GET", "/api/v1/pieces"),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        assertThat(output).doesNotContain("HTTP request");
    }

    @Test
    void logsSalesEvenWithTheRestOfTheAppOff(CapturedOutput output) throws Exception {
        try (AutoCloseable off = LogLevels.set("br.com.luma", Level.OFF);
                AutoCloseable sales = LogLevels.set(SalesLog.LOGGER_NAME, Level.INFO)) {
            SalesLog.saleRegistered(UUID.randomUUID(), "C-2026-003", UUID.randomUUID(), "LU-0042", UUID.randomUUID(),
                    8_490, 30, java.time.Instant.parse("2026-11-21T18:03:00Z"), false);
        }
        assertThat(output).contains("Sale registered", "consignmentCode=C-2026-003", "pieceCode=LU-0042",
                "valueCents=8490", "commissionRate=30", "retroactive=false");
    }
}
