package br.com.luma.shared.logging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Uma linha por requisição no logger {@code br.com.luma.access}: método, caminho (sem a query, que pode ter
 * texto digitado), status e duração. Lentas saem em WARN. Com o logger em OFF, o filtro não mede nada.
 *
 * Fica dentro do filtro de observação (traceId no MDC) e fora da cadeia de segurança, para cobrir também os
 * 401/403. É ele quem limpa o userId/role que o {@link UserMdcFilter} põe no MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessLogFilter extends OncePerRequestFilter {

    public static final String LOGGER_NAME = "br.com.luma.access";
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private final long slowRequestMs;

    public AccessLogFilter(@Value("${app.logging.slow-request-ms:1000}") long slowRequestMs) {
        this.slowRequestMs = slowRequestMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        UserMdcFilter.clear();
        String path = request.getRequestURI();
        boolean enabled = LOGGER.isWarnEnabled() && !isMonitoring(path);
        long start = enabled ? System.nanoTime() : 0L;
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                if (enabled) log(request.getMethod(), path, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            } finally {
                UserMdcFilter.clear();
            }
        }
    }

    private void log(String method, String path, int status, long durationMs) {
        if (durationMs >= slowRequestMs) {
            LOGGER.warn("HTTP request method={} path={} status={} durationMs={} slow=true", method, path, status,
                    durationMs);
        } else if (LOGGER.isInfoEnabled()) {
            LOGGER.info("HTTP request method={} path={} status={} durationMs={}", method, path, status, durationMs);
        }
    }

    // Chamadas do Railway e do monitoramento, que acontecem o tempo todo.
    private static boolean isMonitoring(String path) {
        return path.startsWith("/actuator/health") || path.equals("/actuator/prometheus");
    }
}
