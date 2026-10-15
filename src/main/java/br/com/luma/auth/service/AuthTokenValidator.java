package br.com.luma.auth.service;

import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import br.com.luma.auth.repository.UserRepository;

@Component
public class AuthTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final UserRepository userRepository;

    public AuthTokenValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            Object versionClaim = jwt.getClaims().get("authVersion");
            if (!(versionClaim instanceof Number authVersion)) {
                return invalidToken();
            }
            return userRepository.findById(userId)
                    .filter(user -> user.isActive() && user.getAuthVersion() == authVersion.longValue())
                    .map(user -> OAuth2TokenValidatorResult.success())
                    .orElseGet(AuthTokenValidator::invalidToken);
        } catch (IllegalArgumentException exception) {
            return invalidToken();
        }
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The access token is no longer valid", null));
    }
}