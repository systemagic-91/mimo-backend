package br.com.luma.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import br.com.luma.auth.exception.AuthException;

class LoginRateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, 3, WINDOW);

    @Test
    void blocksAfterMaxAttemptsWithinTheWindow() {
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");

        assertThatThrownBy(() -> limiter.acquire("10.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("RATE_LIMITED"));
    }

    @Test
    void tracksEachClientIpSeparately() {
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");

        assertThatCode(() -> limiter.acquire("10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginResetsTheCounter() {
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");
        limiter.recordSuccess("10.0.0.1");

        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");
        assertThatCode(() -> limiter.acquire("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void windowExpiryAllowsNewAttempts() {
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.1");

        clock.advance(WINDOW);

        assertThatCode(() -> limiter.acquire("10.0.0.1")).doesNotThrowAnyException();
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
