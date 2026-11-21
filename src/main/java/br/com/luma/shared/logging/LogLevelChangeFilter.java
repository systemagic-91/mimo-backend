package br.com.luma.shared.logging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Registra quem mudou o nível de log pelo Actuator ({@code POST /actuator/loggers/{nome}}), para não ficar um
 * "buraco" sem explicação nos logs. Roda dentro da cadeia de segurança, depois do {@link UserMdcFilter}.
 * Usa um logger próprio, ligado em INFO por padrão: no modo econômico o resto de {@code br.com.luma} fica em
 * ERROR, e a mudança de nível precisa aparecer mesmo assim.
 */
public class LogLevelChangeFilter extends OncePerRequestFilter {

    public static final String LOGGER_NAME = "br.com.luma.loglevel";
    static final String PREFIX = "/actuator/loggers/";
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private final ObjectMapper objectMapper;

    public LogLevelChangeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request, 1024);
        filterChain.doFilter(wrapper, response);
        if (response.getStatus() >= 200 && response.getStatus() < 300) {
            String logger = request.getRequestURI().substring(PREFIX.length());
            LOGGER.info("Log level changed logger={} level={}", logger, configuredLevel(wrapper.getContentAsByteArray()));
        }
    }

    private String configuredLevel(byte[] body) {
        try {
            JsonNode level = objectMapper.readTree(body).path("configuredLevel");
            return level.isMissingNode() || level.isNull() ? "default" : level.asText();
        } catch (IOException exception) {
            return "unknown";
        }
    }
}
