package br.com.luma.shared.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService idempotencyService;

    public IdempotencyCleanupJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(cron = "${app.idempotency.cleanup.cron:0 30 3 * * *}",
            zone = "${app.idempotency.cleanup.zone:America/Sao_Paulo}")
    public void deleteExpiredKeys() {
        int deleted = idempotencyService.deleteExpired();
        LOGGER.info("Expired idempotency keys deleted count={}", deleted);
    }
}
