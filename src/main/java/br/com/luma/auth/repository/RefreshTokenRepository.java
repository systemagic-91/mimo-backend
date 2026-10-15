package br.com.luma.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.auth.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshTokenEntity token where token.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenEntity token set token.revokedAt = :revokedAt "
            + "where token.user.id = :userId and token.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") java.time.Instant revokedAt);
}