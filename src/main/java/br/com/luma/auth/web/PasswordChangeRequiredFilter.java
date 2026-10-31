package br.com.luma.auth.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.luma.auth.service.AccessTokenService;
import br.com.luma.shared.web.ProblemDetailWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/change-password",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh");

    private final ProblemDetailWriter problemDetailWriter;

    public PasswordChangeRequiredFilter(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (mustChangePassword() && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            problemDetailWriter.write(request, response, HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                    "Troque a senha temporária antes de continuar.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean mustChangePassword() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && Boolean.TRUE.equals(jwt.getClaimAsBoolean(AccessTokenService.MUST_CHANGE_PASSWORD_CLAIM));
    }
}
