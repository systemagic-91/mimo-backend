package br.com.luma.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import br.com.luma.auth.exception.AuthException;

@Component
public class LoginRateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final Duration windowDuration;

    public LoginRateLimiter(Clock clock,
            @Value("${app.security.login-rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${app.security.login-rate-limit.window:PT15M}") Duration windowDuration) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.windowDuration = windowDuration;
    }

    // Conta por IP e por e-mail: o IP vem do X-Forwarded-For e pode ser inventado por quem chama o Railway direto.
    public synchronized void acquire(String clientIp, String email) {
        Instant now = clock.instant();
        removeExpired(now);
        Window ipWindow = window("ip:" + clientIp, now);
        Window emailWindow = window("email:" + email, now);
        if (ipWindow.attempts >= maxAttempts || emailWindow.attempts >= maxAttempts) {
            throw rateLimited();
        }
        ipWindow.attempts++;
        emailWindow.attempts++;
    }

    public synchronized void recordSuccess(String clientIp, String email) {
        windows.remove("ip:" + clientIp);
        windows.remove("email:" + email);
    }

    private Window window(String key, Instant now) {
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= MAX_TRACKED_CLIENTS) {
                throw rateLimited();
            }
            window = new Window(now);
            windows.put(key, window);
        }
        return window;
    }

    private void removeExpired(Instant now) {
        Iterator<Window> iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().startedAt.plus(windowDuration).isAfter(now)) {
                iterator.remove();
            }
        }
    }

    private static AuthException rateLimited() {
        return new AuthException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Muitas tentativas. Tente novamente mais tarde.");
    }

    private static final class Window {
        private final Instant startedAt;
        private int attempts;

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}