package br.com.luma.upload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PhotoCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhotoCleanupJob.class);

    private final PhotoApplicationService photoService;

    public PhotoCleanupJob(PhotoApplicationService photoService) {
        this.photoService = photoService;
    }

    @Scheduled(cron = "${app.uploads.orphan-cleanup.cron:0 0 4 * * *}",
            zone = "${app.uploads.orphan-cleanup.zone:America/Sao_Paulo}")
    public void deleteOrphanPhotos() {
        int deleted = photoService.deleteOrphans();
        LOGGER.info("Orphan piece photos deleted count={}", deleted);
    }
}
