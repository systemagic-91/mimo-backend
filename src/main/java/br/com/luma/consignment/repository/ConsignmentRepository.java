package br.com.luma.consignment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.shared.ConsignmentStatus;

public interface ConsignmentRepository extends JpaRepository<ConsignmentEntity, UUID> {
    Optional<ConsignmentEntity> findByCode(String code);

    List<ConsignmentEntity> findAllByStatusAndDueAtBeforeOrderByDueAtAsc(
            ConsignmentStatus status, Instant dueAt);
}