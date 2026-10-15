package br.com.luma.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import br.com.luma.auth.entity.IdempotencyKeyEntity;
import br.com.luma.auth.repository.IdempotencyKeyRepository;
import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.exception.ApiException;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository, UserRepository userRepository,
            ObjectMapper objectMapper, Clock clock,
            @Value("${app.idempotency.ttl:P1D}") Duration ttl) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public IdempotentResponse execute(UUID userId, String operation, String idempotencyKey,
            Object requestPayload, Supplier<IdempotentResponse> operationAction) {
        String scopedKey = scopedKey(operation, idempotencyKey);
        String fingerprint = fingerprint(requestPayload);
        Instant now = clock.instant();
        int claimed = idempotencyKeyRepository.claim(UUID.randomUUID(), userId, scopedKey,
                fingerprint, now, now.plus(ttl));
        IdempotencyKeyEntity entry = idempotencyKeyRepository.findForUpdate(userId, scopedKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency claim was not persisted"));

        if (claimed == 0) {
            if (!entry.getFingerprint().equals(fingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "A chave Idempotency-Key já foi usada com outro payload.");
            }
            if (entry.getResponseStatus() == null) {
                throw conflict("IDEMPOTENCY_IN_PROGRESS", "Uma operação com esta chave ainda está em andamento.");
            }
            return new IdempotentResponse(entry.getResponseStatus(), entry.getResponseBody());
        }

        entry.setUser(userRepository.getReferenceById(userId));
        IdempotentResponse result = operationAction.get();
        if (result == null || result.status() < 100 || result.status() > 599) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_IDEMPOTENT_RESPONSE",
                    "A operação retornou uma resposta inválida.");
        }
        entry.setResponseStatus((short) result.status());
        entry.setResponseBody(result.body() == null ? null : result.body().deepCopy());
        idempotencyKeyRepository.save(entry);
        return result;
    }

    private String scopedKey(String operation, String key) {
        if (operation == null || !operation.matches("[a-zA-Z0-9._-]{1,80}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_SCOPE",
                    "O escopo da operação é inválido.");
        }
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "O header Idempotency-Key é obrigatório e deve ter até 160 caracteres.");
        }
        return operation + ":" + key;
    }

    private String fingerprint(Object payload) {
        try {
            JsonNode canonicalPayload = canonicalize(objectMapper.valueToTree(payload));
            byte[] bytes = objectMapper.writeValueAsBytes(canonicalPayload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_PAYLOAD",
                    "O payload não pode ser serializado para validação de idempotência.");
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isValueNode()) {
            return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        names.forEach(name -> result.set(name, canonicalize(value.get(name))));
        return result;
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}