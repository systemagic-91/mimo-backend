package br.com.luma.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.auth.entity.IdempotencyKeyEntity;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
    Optional<IdempotencyKeyEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}