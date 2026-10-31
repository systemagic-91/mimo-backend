package br.com.luma.upload.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import br.com.luma.shared.exception.ApiException;

@Component
public class PhotoUploadRateLimiter {

    private final Map<UUID, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int maxUploads;
    private final Duration windowDuration;

    public PhotoUploadRateLimiter(Clock clock,
            @Value("${app.uploads.rate-limit.max-uploads:30}") int maxUploads,
            @Value("${app.uploads.rate-limit.window:PT1M}") Duration windowDuration) {
        this.clock = clock;
        this.maxUploads = maxUploads;
        this.windowDuration = windowDuration;
    }

    public synchronized void acquire(UUID userId) {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !window.startedAt.plus(windowDuration).isAfter(now));
        Window window = windows.computeIfAbsent(userId, ignored -> new Window(now));
        if (window.uploads >= maxUploads) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Muitos envios de foto. Tente novamente em instantes.");
        }
        window.uploads++;
    }

    private static final class Window {
        private final Instant startedAt;
        private int uploads;

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
