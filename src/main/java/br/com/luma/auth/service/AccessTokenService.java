package br.com.luma.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import br.com.luma.auth.entity.UserEntity;

@Service
public class AccessTokenService {

    // Toda mudança de users.must_change_password incrementa o authVersion, então o claim nunca fica desatualizado.
    public static final String MUST_CHANGE_PASSWORD_CLAIM = "mustChangePassword";

    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String issuer;
    private final Duration accessTokenTtl;

    public AccessTokenService(JwtEncoder jwtEncoder, Clock clock,
            @Value("${app.security.jwt.issuer}") String issuer,
            @Value("${app.security.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    public IssuedAccessToken issue(UserEntity user, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of("ROLE_" + user.getRole().name()))
                .claim("authVersion", user.getAuthVersion())
                .claim(MUST_CHANGE_PASSWORD_CLAIM, user.isMustChangePassword())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedAccessToken(token, issuedAt, expiresAt);
    }

    public IssuedAccessToken issue(UserEntity user) {
        return issue(user, clock.instant());
    }

    public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt) {
    }
}