package br.com.luma.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import br.com.luma.shared.exception.ApiException;

class PhotoUploadRateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
    private final PhotoUploadRateLimiter limiter = new PhotoUploadRateLimiter(clock, 2, WINDOW);

    @Test
    void blocksAfterMaxUploadsWithinTheWindow() {
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        limiter.acquire(user);

        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exception.getCode()).isEqualTo("RATE_LIMITED");
        });
    }

    @Test
    void tracksEachUserSeparately() {
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        limiter.acquire(user);

        assertThatCode(() -> limiter.acquire(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void windowExpiryAllowsNewUploads() {
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        limiter.acquire(user);

        clock.advance(WINDOW);

        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
