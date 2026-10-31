package br.com.luma.auth.service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.metrics.BusinessMetrics;
import br.com.luma.shared.repository.AuditLogRepository;

@Service
public class AuthenticationAuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final BusinessMetrics metrics;
    private final Clock clock;

    public AuthenticationAuditService(AuditLogRepository auditLogRepository, UserRepository userRepository,
            BusinessMetrics metrics, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLogin(UUID actorId, String action, String result, String ipAddress) {
        AuditLogEntity audit = new AuditLogEntity();
        UserEntity actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        audit.setActor(actor);
        audit.setAction(action);
        audit.setEntityType("AUTHENTICATION");
        audit.setEntityId(actorId == null ? UUID.randomUUID() : actorId);
        audit.setAfterState(Map.of("result", result));
        audit.setIpAddress(ipAddress);
        audit.setOccurredAt(clock.instant());
        auditLogRepository.save(audit);
        if ("LOGIN_SUCCEEDED".equals(action) || "LOGIN_FAILED".equals(action)) {
            metrics.login("LOGIN_SUCCEEDED".equals(action));
        }
    }
}