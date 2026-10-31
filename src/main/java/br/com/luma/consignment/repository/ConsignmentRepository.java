package br.com.luma.consignment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.shared.ConsignmentStatus;
import jakarta.persistence.LockModeType;

public interface ConsignmentRepository extends JpaRepository<ConsignmentEntity, UUID> {
    Optional<ConsignmentEntity> findByCode(String code);

        Page<ConsignmentEntity> findAllBySeller_IdOrderByDeliveredAtDesc(UUID sellerId, Pageable pageable);

        Page<ConsignmentEntity> findAllBySeller_IdAndStatusOrderByDeliveredAtDesc(
            UUID sellerId, ConsignmentStatus status, Pageable pageable);

    List<ConsignmentEntity> findAllByStatusAndDueAtBeforeOrderByDueAtAsc(
            ConsignmentStatus status, Instant dueAt);

    @Query(value = """
            select consignment from ConsignmentEntity consignment
            join fetch consignment.seller seller
            where (:status is null or consignment.status = :status)
              and (:sellerId is null or seller.id = :sellerId)
              and (:overdue = 0
                   or (:overdue = 1 and consignment.status = br.com.luma.shared.ConsignmentStatus.ABERTA
                       and consignment.dueAt < :now)
                   or (:overdue = 2 and (consignment.status = br.com.luma.shared.ConsignmentStatus.QUITADA
                       or consignment.dueAt >= :now)))
            """, countQuery = """
            select count(consignment) from ConsignmentEntity consignment
            where (:status is null or consignment.status = :status)
              and (:sellerId is null or consignment.seller.id = :sellerId)
              and (:overdue = 0
                   or (:overdue = 1 and consignment.status = br.com.luma.shared.ConsignmentStatus.ABERTA
                       and consignment.dueAt < :now)
                   or (:overdue = 2 and (consignment.status = br.com.luma.shared.ConsignmentStatus.QUITADA
                       or consignment.dueAt >= :now)))
            """)
    Page<ConsignmentEntity> search(@Param("status") ConsignmentStatus status, @Param("sellerId") UUID sellerId,
            @Param("overdue") int overdue, @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select consignment from ConsignmentEntity consignment where consignment.id = :id")
    Optional<ConsignmentEntity> findByIdForUpdate(@Param("id") UUID id);
}
