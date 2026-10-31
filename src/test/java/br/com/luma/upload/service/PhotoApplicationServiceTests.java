package br.com.luma.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.upload.entity.PiecePhotoEntity;
import br.com.luma.upload.repository.PiecePhotoRepository;

@ExtendWith(MockitoExtension.class)
class PhotoApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Mock
    private PiecePhotoRepository photoRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PhotoUploadRateLimiter rateLimiter;
    @Mock
    private IdempotencyService idempotencyService;

    private PhotoApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PhotoApplicationService(photoRepository, userRepository, new PhotoInspector(), rateLimiter,
                idempotencyService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(idempotencyService.execute(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentResponse>>getArgument(4).get());
        lenient().when(photoRepository.save(any())).thenAnswer(invocation -> {
            PiecePhotoEntity photo = invocation.getArgument(0);
            photo.setId(UUID.randomUUID());
            return photo;
        });
    }

    @Test
    void storesValidImageUsingDetectedTypeInsteadOfClientMetadata() throws IOException {
        byte[] png = png(320, 240);
        MockMultipartFile file = new MockMultipartFile("file", "evil.svg", "image/svg+xml", png);

        IdempotentResponse response = service.upload(ACTOR_ID, "key", file);

        PiecePhotoEntity photo = savedPhoto();
        assertThat(photo.getContent()).isEqualTo(png);
        assertThat(photo.getContentType()).isEqualTo("image/png");
        assertThat(photo.getWidth()).isEqualTo(320);
        assertThat(photo.getHeight()).isEqualTo(240);
        assertThat(photo.getSizeBytes()).isEqualTo(png.length);
        assertThat(photo.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("photoId").asText()).isEqualTo(photo.getId().toString());
        assertThat(response.body().get("photoUrl").asText()).isEqualTo("/api/v1/photos/" + photo.getId());
        assertThat(response.body().get("width").asInt()).isEqualTo(320);
        assertThat(response.body().get("height").asInt()).isEqualTo(240);
        verify(rateLimiter).acquire(ACTOR_ID);
    }

    @Test
    void idempotencyFingerprintIsTheContentHashNotTheBytes() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png(10, 10));

        service.upload(ACTOR_ID, "key", file);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
        verify(idempotencyService).execute(eq(ACTOR_ID), eq("uploads.piece-photo"), eq("key"),
                payload.capture(), any());
        assertThat(payload.getValue()).containsOnlyKeys("sha256");
        assertThat(payload.getValue().get("sha256")).hasSize(64);
    }

    @Test
    void invalidImageIsRejectedWithoutSaving() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png",
                "<svg></svg>".getBytes(StandardCharsets.UTF_8));

        assertApiError(() -> service.upload(ACTOR_ID, "key", file), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        verify(photoRepository, never()).save(any());
    }

    @Test
    void emptyFileIsRejectedBeforeRateLimitAndIdempotency() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[0]);

        assertApiError(() -> service.upload(ACTOR_ID, "key", file), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        verifyNoInteractions(rateLimiter, idempotencyService, photoRepository);
    }

    @Test
    void fileLargerThanOneMegabyteReturnsPayloadTooLarge() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                new byte[PhotoApplicationService.MAX_SIZE_BYTES + 1]);

        assertApiError(() -> service.upload(ACTOR_ID, "key", file), HttpStatus.PAYLOAD_TOO_LARGE,
                "PHOTO_TOO_LARGE");
        verifyNoInteractions(rateLimiter, idempotencyService, photoRepository);
    }

    @Test
    void rateLimitedUploadIsNotStored() throws IOException {
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow down"))
                .when(rateLimiter).acquire(ACTOR_ID);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png(10, 10));

        assertApiError(() -> service.upload(ACTOR_ID, "key", file), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        verifyNoInteractions(idempotencyService, photoRepository);
    }

    @Test
    void findReturnsStoredBytesAndTypeOrNotFound() {
        PiecePhotoEntity photo = new PiecePhotoEntity();
        photo.setId(UUID.randomUUID());
        photo.setContentType("image/webp");
        photo.setContent(new byte[] { 1, 2, 3 });
        when(photoRepository.findById(photo.getId())).thenReturn(Optional.of(photo));
        UUID missing = UUID.randomUUID();
        when(photoRepository.findById(missing)).thenReturn(Optional.empty());

        PhotoContent content = service.find(photo.getId());

        assertThat(content.contentType()).isEqualTo("image/webp");
        assertThat(content.content()).containsExactly(1, 2, 3);
        assertApiError(() -> service.find(missing), HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
    }

    @Test
    void requireExistingFailsForUnknownPhoto() {
        UUID missing = UUID.randomUUID();
        when(photoRepository.existsById(missing)).thenReturn(false);

        assertApiError(() -> service.requireExisting(missing), HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
    }

    @Test
    void orphanCleanupOnlyTargetsPhotosOlderThanOneDay() {
        when(photoRepository.deleteOrphansCreatedBefore(NOW.minusSeconds(24 * 60 * 60))).thenReturn(3);

        assertThat(service.deleteOrphans()).isEqualTo(3);
    }

    private PiecePhotoEntity savedPhoto() {
        ArgumentCaptor<PiecePhotoEntity> photo = ArgumentCaptor.forClass(PiecePhotoEntity.class);
        verify(photoRepository).save(photo.capture());
        return photo.getValue();
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
