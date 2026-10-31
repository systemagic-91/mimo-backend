package br.com.luma.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Para esconder dados só de owner (custos, valores devidos) nas respostas compartilhadas com staff.
@Component
public class CurrentUserRoles {

    public boolean isOwner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OWNER".equals(authority.getAuthority()));
    }
}
