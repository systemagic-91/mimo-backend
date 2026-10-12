package br.com.luma.shared.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.shared.entity.CodeSequenceEntity;
import jakarta.persistence.LockModeType;

public interface CodeSequenceRepository extends JpaRepository<CodeSequenceEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sequence from CodeSequenceEntity sequence where sequence.scope = :scope and sequence.sequenceYear = :year")
    Optional<CodeSequenceEntity> findForUpdate(@Param("scope") String scope, @Param("year") short year);
}