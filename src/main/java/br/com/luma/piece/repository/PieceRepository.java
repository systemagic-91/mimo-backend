package br.com.luma.piece.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.shared.PieceStage;
import jakarta.persistence.LockModeType;

public interface PieceRepository extends JpaRepository<PieceEntity, UUID> {
    Optional<PieceEntity> findByCode(String code);

    @Query("select piece from PieceEntity piece "
            + "where (:status is null or piece.status = :status) "
            + "and (cast(:query as String) is null or lower(piece.code) like lower(concat('%', cast(:query as String), '%')) "
            + "or lower(piece.type) like lower(concat('%', cast(:query as String), '%')))")
    Page<PieceEntity> search(@Param("query") String query, @Param("status") PieceStage status,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select piece from PieceEntity piece where piece.id = :id")
    Optional<PieceEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select piece from PieceEntity piece where piece.id in :ids")
    List<PieceEntity> findAllForUpdate(@Param("ids") Collection<UUID> ids);
}