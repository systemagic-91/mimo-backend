package br.com.luma.consignment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.shared.ConsignmentStatus;

public interface ConsignmentRepository extends JpaRepository<ConsignmentEntity, UUID> {
    Optional<ConsignmentEntity> findByCode(String code);

        Page<ConsignmentEntity> findAllBySeller_IdOrderByDeliveredAtDesc(UUID sellerId, Pageable pageable);

        Page<ConsignmentEntity> findAllBySeller_IdAndStatusOrderByDeliveredAtDesc(
            UUID sellerId, ConsignmentStatus status, Pageable pageable);

    List<ConsignmentEntity> findAllByStatusAndDueAtBeforeOrderByDueAtAsc(
            ConsignmentStatus status, Instant dueAt);
}