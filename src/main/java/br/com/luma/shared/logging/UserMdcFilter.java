package br.com.luma.shared.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Põe no MDC quem está usando ({@code userId} e {@code role}) depois que o JWT é validado, para toda linha de
 * log da requisição dizer quem fez. Roda dentro da cadeia de segurança. Não limpa no fim de propósito: o
 * {@link AccessLogFilter}, mais externo, ainda loga a linha de acesso com esses campos e limpa em seguida.
 */
public class UserMdcFilter extends OncePerRequestFilter {

    public static final String USER_ID = "userId";
    public static final String ROLE = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            MDC.put(USER_ID, jwt.getName());
            MDC.put(ROLE, roleOf(jwt));
        }
        filterChain.doFilter(request, response);
    }

    static void clear() {
        MDC.remove(USER_ID);
        MDC.remove(ROLE);
    }

    private static String roleOf(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_OWNER".equals(authority.getAuthority())) return "owner";
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_STAFF".equals(authority.getAuthority())) return "staff";
        }
        return "unknown";
    }
}
