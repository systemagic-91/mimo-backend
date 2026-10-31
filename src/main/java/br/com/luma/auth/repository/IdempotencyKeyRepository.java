package br.com.luma.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.auth.entity.IdempotencyKeyEntity;
import jakarta.persistence.LockModeType;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
    Optional<IdempotencyKeyEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys (id, user_id, idempotency_key, fingerprint, created_at, expires_at)
            VALUES (:id, :userId, :key, :fingerprint, :createdAt, :expiresAt)
            ON CONFLICT (user_id, idempotency_key) DO UPDATE SET
                fingerprint = EXCLUDED.fingerprint,
                response_status = NULL,
                response_body = NULL,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at
            WHERE idempotency_keys.expires_at <= :createdAt
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("userId") UUID userId, @Param("key") String key,
            @Param("fingerprint") String fingerprint, @Param("createdAt") java.time.Instant createdAt,
            @Param("expiresAt") java.time.Instant expiresAt);

    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE expires_at < :cutoff", nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") java.time.Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from IdempotencyKeyEntity entry "
            + "where entry.user.id = :userId and entry.idempotencyKey = :key")
    Optional<IdempotencyKeyEntity> findForUpdate(@Param("userId") UUID userId, @Param("key") String key);
}