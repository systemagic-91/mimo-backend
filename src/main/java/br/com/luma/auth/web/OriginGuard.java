package br.com.luma.auth.web;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import br.com.luma.shared.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

// Proteção contra CSRF nas rotas que usam o cookie. Pedido sem Origin não vem de navegador, então não é CSRF.
@Component
public class OriginGuard {

    private final Set<String> allowedOrigins;

    public OriginGuard(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void check(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "Origem não permitida.");
        }
    }
}
