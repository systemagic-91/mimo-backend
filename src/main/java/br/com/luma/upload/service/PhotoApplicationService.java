package br.com.luma.upload.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.luma.auth.repository.UserRepository;
import br.com.luma.shared.audit.AuditTrail;
import br.com.luma.shared.exception.ApiException;
import br.com.luma.shared.idempotency.IdempotencyService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.metrics.BusinessMetrics;
import br.com.luma.upload.PhotoUrls;
import br.com.luma.upload.dto.PhotoUploadResponse;
import br.com.luma.upload.entity.PiecePhotoEntity;
import br.com.luma.upload.repository.PiecePhotoRepository;

@Service
public class PhotoApplicationService {

    public static final int MAX_SIZE_BYTES = 1024 * 1024;
    static final Duration ORPHAN_TTL = Duration.ofHours(24);

    private final PiecePhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final PhotoInspector inspector;
    private final PhotoUploadRateLimiter rateLimiter;
    private final IdempotencyService idempotencyService;
    private final AuditTrail auditTrail;
    private final BusinessMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PhotoApplicationService(PiecePhotoRepository photoRepository, UserRepository userRepository,
            PhotoInspector inspector, PhotoUploadRateLimiter rateLimiter, IdempotencyService idempotencyService,
            AuditTrail auditTrail, BusinessMetrics metrics, ObjectMapper objectMapper, Clock clock) {
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.inspector = inspector;
        this.rateLimiter = rateLimiter;
        this.idempotencyService = idempotencyService;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public IdempotentResponse upload(UUID actorId, String idempotencyKey, MultipartFile file, String ipAddress) {
        byte[] content = readContent(file);
        rateLimiter.acquire(actorId);
        return idempotencyService.execute(actorId, "uploads.piece-photo", idempotencyKey,
                Map.of("sha256", sha256(content)), () -> {
                    PhotoMetadata metadata = inspector.inspect(content);
                    PiecePhotoEntity photo = new PiecePhotoEntity();
                    photo.setContent(content);
                    photo.setContentType(metadata.contentType());
                    photo.setWidth(metadata.width());
                    photo.setHeight(metadata.height());
                    photo.setSizeBytes(content.length);
                    photo.setCreatedBy(userRepository.getReferenceById(actorId));
                    photo.setCreatedAt(clock.instant());
                    photo = photoRepository.save(photo);
                    auditTrail.record(actorId, "PHOTO_UPLOADED", "PHOTO", photo.getId(), ipAddress, Map.of(
                            "contentType", metadata.contentType(), "width", metadata.width(),
                            "height", metadata.height(), "sizeBytes", content.length));
                    metrics.photoUploaded(content.length);

                    PhotoUploadResponse response = new PhotoUploadResponse(photo.getId(),
                            PhotoUrls.of(photo.getId()), metadata.width(), metadata.height());
                    return new IdempotentResponse(201, objectMapper.valueToTree(response));
                });
    }

    @Transactional(readOnly = true)
    public PhotoContent find(UUID photoId) {
        PiecePhotoEntity photo = photoRepository.findById(photoId).orElseThrow(() -> photoNotFound(photoId));
        return new PhotoContent(photo.getContentType(), photo.getContent());
    }

    @Transactional(readOnly = true)
    public void requireExisting(UUID photoId) {
        if (!photoRepository.existsById(photoId)) {
            throw photoNotFound(photoId);
        }
    }

    @Transactional
    public int deleteOrphans() {
        return photoRepository.deleteOrphansCreatedBefore(clock.instant().minus(ORPHAN_TTL));
    }

    private static byte[] readContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PhotoInspector.invalid("Envie o arquivo da foto no campo file.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw photoTooLarge();
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw PhotoInspector.invalid("Não foi possível ler o arquivo enviado.");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public static ApiException photoTooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE",
                "A foto deve ter no máximo 1 MB.");
    }

    private static ApiException photoNotFound(UUID photoId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND",
                "A foto " + photoId + " não foi encontrada.");
    }
}
