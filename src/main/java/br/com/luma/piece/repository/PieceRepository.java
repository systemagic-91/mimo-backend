package br.com.luma.piece.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.piece.entity.PieceEntity;
import jakarta.persistence.LockModeType;

public interface PieceRepository extends JpaRepository<PieceEntity, UUID> {
    Optional<PieceEntity> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select piece from PieceEntity piece where piece.id in :ids")
    List<PieceEntity> findAllForUpdate(@Param("ids") Collection<UUID> ids);
}