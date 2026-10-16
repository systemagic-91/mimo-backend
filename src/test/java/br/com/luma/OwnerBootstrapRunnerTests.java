package br.com.luma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.auth.service.OwnerBootstrapRunner;
import br.com.luma.shared.Role;

@ExtendWith(MockitoExtension.class)
class OwnerBootstrapRunnerTests {

    @Mock
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Test
    void provisionsOwnerWithNormalizedEmailAndBcryptPassword() throws Exception {
        when(userRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.empty());
        OwnerBootstrapRunner runner = new OwnerBootstrapRunner(userRepository, passwordEncoder,
                "Store Owner", " Owner@Example.com ", "UniquePassword-123");

        runner.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<UserEntity> savedUser = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getName()).isEqualTo("Store Owner");
        assertThat(savedUser.getValue().getEmail()).isEqualTo("owner@example.com");
        assertThat(savedUser.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(savedUser.getValue().getPasswordHash()).isNotEqualTo("UniquePassword-123");
        assertThat(passwordEncoder.matches("UniquePassword-123", savedUser.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void refusesToOverwriteAnExistingAccount() {
        when(userRepository.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.of(new UserEntity()));
        OwnerBootstrapRunner runner = new OwnerBootstrapRunner(userRepository, passwordEncoder,
                "Store Owner", "owner@example.com", "UniquePassword-123");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsMissingOrShortBootstrapPassword() {
        OwnerBootstrapRunner runner = new OwnerBootstrapRunner(userRepository, passwordEncoder,
                "Store Owner", "owner@example.com", "short");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12-72 character password");
        verify(userRepository, never()).save(any());
    }
}