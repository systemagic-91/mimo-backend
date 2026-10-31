package br.com.luma.consignment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.shared.ConsignmentItemStatus;
import jakarta.persistence.LockModeType;

public interface ConsignmentItemRepository extends JpaRepository<ConsignmentItemEntity, UUID> {
    List<ConsignmentItemEntity> findAllByPiece_IdOrderByConsignment_DeliveredAtDesc(UUID pieceId);

    List<ConsignmentItemEntity> findAllByConsignment_IdAndStatus(
            UUID consignmentId, ConsignmentItemStatus status);

    boolean existsByPiece_IdAndStatus(UUID pieceId, ConsignmentItemStatus status);

    List<ConsignmentItemEntity> findAllByConsignment_IdOrderByCodeSnapshotAsc(UUID consignmentId);

    List<ConsignmentItemEntity> findAllByPiece_IdInAndStatus(Collection<UUID> pieceIds, ConsignmentItemStatus status);

    long countByConsignment_IdAndStatus(UUID consignmentId, ConsignmentItemStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ConsignmentItemEntity item "
            + "where item.consignment.id = :consignmentId and item.piece.id = :pieceId")
    Optional<ConsignmentItemEntity> findForUpdate(@Param("consignmentId") UUID consignmentId,
            @Param("pieceId") UUID pieceId);

    @Query("select item.consignment.id as consignmentId, count(item) as piecesInHand, "
            + "coalesce(sum(item.valueCentsSnapshot), 0) as valueInHandCents "
            + "from ConsignmentItemEntity item "
            + "where item.consignment.id in :consignmentIds "
            + "and item.status = br.com.luma.shared.ConsignmentItemStatus.COM_VENDEDORA "
            + "group by item.consignment.id")
    List<InHandSummary> summarizeInHand(@Param("consignmentIds") Collection<UUID> consignmentIds);

    interface InHandSummary {
        UUID getConsignmentId();

        long getPiecesInHand();

        long getValueInHandCents();
    }
}
