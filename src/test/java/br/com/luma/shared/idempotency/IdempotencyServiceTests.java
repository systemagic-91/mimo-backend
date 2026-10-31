package br.com.luma.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.entity.IdempotencyKeyEntity;
import br.com.luma.auth.repository.IdempotencyKeyRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.exception.ApiException;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(1);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SCOPED_KEY = "sellers.create:key-1";

    @Mock
    private IdempotencyKeyRepository repository;
    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotencyKeyEntity entry = new IdempotencyKeyEntity();
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, userRepository, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), TTL);
    }

    @Test
    void firstExecutionRunsTheOperationAndStoresItsResponse() {
        claimSucceeds();
        AtomicInteger runs = new AtomicInteger();

        IdempotentResponse response = service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"),
                () -> created(runs));

        assertThat(response.status()).isEqualTo(201);
        assertThat(runs).hasValue(1);
        assertThat(entry.getResponseStatus()).isEqualTo((short) 201);
        assertThat(entry.getResponseBody().get("run").asInt()).isEqualTo(1);
        verify(repository).claim(any(), eq(USER_ID), eq(SCOPED_KEY), anyString(), eq(NOW), eq(NOW.plus(TTL)));
        verify(repository).save(entry);
    }

    @Test
    void retryWithSamePayloadReplaysStoredResponseWithoutRunningAgain() {
        claimSucceeds();
        AtomicInteger runs = new AtomicInteger();
        service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"), () -> created(runs));
        doReturn(0).when(repository).claim(any(), any(), any(), any(), any(), any());

        IdempotentResponse replay = service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"),
                () -> created(runs));

        assertThat(runs).hasValue(1);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("run").asInt()).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsAConflict() {
        claimSucceeds();
        AtomicInteger runs = new AtomicInteger();
        service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"), () -> created(runs));
        doReturn(0).when(repository).claim(any(), any(), any(), any(), any(), any());

        assertApiError(() -> service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Bia"),
                () -> created(runs)), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        assertThat(runs).hasValue(1);
    }

    @Test
    void keyStillWithoutResponseIsReportedAsInProgress() {
        claimSucceeds();
        AtomicInteger runs = new AtomicInteger();
        service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"), () -> created(runs));
        entry.setResponseStatus(null);
        doReturn(0).when(repository).claim(any(), any(), any(), any(), any(), any());

        assertApiError(() -> service.execute(USER_ID, "sellers.create", "key-1", Map.of("name", "Ana"),
                () -> created(runs)), HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void fingerprintIgnoresJsonFieldOrder() {
        when(repository.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.findForUpdate(any(), any())).thenReturn(Optional.of(entry));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "Ana");
        first.put("phone", "11");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("phone", "11");
        second.put("name", "Ana");

        service.execute(USER_ID, "sellers.create", "key-a", first, () -> created(new AtomicInteger()));
        service.execute(USER_ID, "sellers.create", "key-b", second, () -> created(new AtomicInteger()));

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2))
                .claim(any(), any(), any(), fingerprints.capture(), any(), any());
        assertThat(fingerprints.getAllValues().get(0)).isEqualTo(fingerprints.getAllValues().get(1));
    }

    @Test
    void missingKeyOrInvalidScopeIsRejectedBeforeTouchingTheDatabase() {
        assertApiError(() -> service.execute(USER_ID, "sellers.create", " ", Map.of(), () -> null),
                HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        assertApiError(() -> service.execute(USER_ID, "sellers.create", "k".repeat(161), Map.of(), () -> null),
                HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        assertApiError(() -> service.execute(USER_ID, "bad scope!", "key-1", Map.of(), () -> null),
                HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_SCOPE");
        verify(repository, never()).claim(any(), any(), any(), any(), any(), any());
    }

    private void claimSucceeds() {
        when(repository.claim(any(), eq(USER_ID), eq(SCOPED_KEY), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    entry.setFingerprint(invocation.getArgument(3));
                    return 1;
                });
        when(repository.findForUpdate(USER_ID, SCOPED_KEY)).thenReturn(Optional.of(entry));
    }

    private IdempotentResponse created(AtomicInteger runs) {
        return new IdempotentResponse(201, objectMapper.valueToTree(Map.of("run", runs.incrementAndGet())));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
