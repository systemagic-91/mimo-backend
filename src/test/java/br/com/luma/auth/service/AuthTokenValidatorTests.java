package br.com.luma.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthTokenValidatorTests {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthTokenValidator validator;

    @Test
    void acceptsTokenForActiveUserWithCurrentAuthVersion() {
        UserEntity user = user(true, 2);

        assertThat(validator.validate(jwt(user.getId().toString(), 2L)).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenIssuedBeforePasswordChange() {
        UserEntity user = user(true, 3);

        assertThat(validator.validate(jwt(user.getId().toString(), 2L)).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenForInactiveUser() {
        UserEntity user = user(false, 0);

        assertThat(validator.validate(jwt(user.getId().toString(), 0L)).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutAuthVersionOrWithInvalidSubject() {
        Jwt withoutVersion = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).build();

        assertThat(validator.validate(withoutVersion).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("not-a-uuid", 0L)).hasErrors()).isTrue();
    }

    private UserEntity user(boolean active, long authVersion) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setActive(active);
        user.setAuthVersion(authVersion);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private static Jwt jwt(String subject, long authVersion) {
        return Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(subject)
                .claim("authVersion", authVersion)
                .build();
    }
}
