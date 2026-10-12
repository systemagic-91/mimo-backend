package br.com.luma.piece.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.piece.entity.PieceEventEntity;

public interface PieceEventRepository extends JpaRepository<PieceEventEntity, UUID> {
    List<PieceEventEntity> findAllByPiece_IdOrderByOccurredAtAscIdAsc(UUID pieceId);
}