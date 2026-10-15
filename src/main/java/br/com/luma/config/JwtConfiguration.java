package br.com.luma.config;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class JwtConfiguration {

    @Bean
    SecretKey jwtSecretKey(@Value("${app.security.jwt.secret:}") String configuredSecret,
            Environment environment) {
        byte[] secretBytes;
        if (configuredSecret.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("JWT_SECRET must be configured outside local and test profiles");
            }
            secretBytes = new byte[32];
            new SecureRandom().nextBytes(secretBytes);
        } else {
            try {
                secretBytes = Base64.getDecoder().decode(configuredSecret);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("JWT_SECRET must be a Base64-encoded 256-bit key", exception);
            }
            if (secretBytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET must contain at least 32 decoded bytes");
            }
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey,
            @Value("${app.security.jwt.issuer}") String issuer,
            OAuth2TokenValidator<Jwt> authTokenValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
            .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), authTokenValidator));
        return decoder;
    }
}