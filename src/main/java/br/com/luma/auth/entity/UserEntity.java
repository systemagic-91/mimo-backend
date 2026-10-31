package br.com.luma.auth.entity;

import java.util.ArrayList;
import java.util.List;

import br.com.luma.shared.Role;
import br.com.luma.shared.entity.TimestampedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
@NoArgsConstructor
public class UserEntity extends TimestampedEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @OneToMany(mappedBy = "user", fetch = jakarta.persistence.FetchType.LAZY, cascade = CascadeType.PERSIST)
    private List<RefreshTokenEntity> refreshTokens = new ArrayList<>();
}