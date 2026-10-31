package br.com.luma.shared.audit;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.entity.AuditLogEntity;
import br.com.luma.shared.repository.AuditLogRepository;

@Component
public class AuditTrail {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public AuditTrail(AuditLogRepository auditLogRepository, UserRepository userRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public void record(UUID actorId, String action, String entityType, UUID entityId, String ipAddress,
            Map<String, Object> afterState) {
        record(actorId, action, entityType, entityId, ipAddress, null, afterState);
    }

    public void record(UUID actorId, String action, String entityType, UUID entityId, String ipAddress,
            Map<String, Object> beforeState, Map<String, Object> afterState) {
        AuditLogEntity event = new AuditLogEntity();
        event.setBeforeState(beforeState);
        event.setActor(userRepository.getReferenceById(actorId));
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setAfterState(afterState);
        event.setIpAddress(ipAddress);
        event.setOccurredAt(clock.instant());
        auditLogRepository.save(event);
    }
}
