package br.com.luma.auth.service;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.Role;

@Component
@ConditionalOnProperty(name = "app.security.bootstrap-owner.enabled", havingValue = "true")
public class OwnerBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String name;
    private final String email;
    private final String password;

    public OwnerBootstrapRunner(UserRepository userRepository, PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-owner.name:}") String name,
            @Value("${app.security.bootstrap-owner.email:}") String email,
            @Value("${app.security.bootstrap-owner.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(email)
                || password == null || password.length() < 12 || password.length() > 72) {
            throw new IllegalStateException("Set bootstrap owner name, email, and a 12-72 character password");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new IllegalStateException("Bootstrap owner already exists; disable bootstrap and use password change");
        }

        UserEntity user = new UserEntity();
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.OWNER);
        user.setActive(true);
        userRepository.save(user);
    }
}