package br.com.luma.shared.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.shared.entity.AuditLogEntity;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
}