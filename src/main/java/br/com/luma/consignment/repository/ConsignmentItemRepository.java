package br.com.luma.consignment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.shared.ConsignmentItemStatus;

public interface ConsignmentItemRepository extends JpaRepository<ConsignmentItemEntity, UUID> {
    List<ConsignmentItemEntity> findAllByPiece_IdOrderByConsignment_DeliveredAtDesc(UUID pieceId);

    List<ConsignmentItemEntity> findAllByConsignment_IdAndStatus(
            UUID consignmentId, ConsignmentItemStatus status);

    boolean existsByPiece_IdAndStatus(UUID pieceId, ConsignmentItemStatus status);
}