package br.com.luma.upload.web;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.upload.service.PhotoApplicationService;
import br.com.luma.upload.service.PhotoContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fotos", description = "Upload e download das fotos das peças.")
public class PhotoController {

    static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private final PhotoApplicationService photoService;

    public PhotoController(PhotoApplicationService photoService) {
        this.photoService = photoService;
    }

    @PostMapping(path = "/uploads/piece-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Envia a foto de uma peça (JPEG, PNG ou WebP, até 1 MB e 2000x2000 px)")
    public ResponseEntity<JsonNode> upload(@RequestPart("file") MultipartFile file,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        IdempotentResponse response = photoService.upload(UUID.fromString(jwt.getSubject()), idempotencyKey, file,
                httpRequest.getRemoteAddr());
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/photos/{photoId}")
    @Operation(summary = "Devolve a imagem de uma foto; é público para funcionar em <img>")
    public ResponseEntity<byte[]> photo(@PathVariable UUID photoId) {
        PhotoContent photo = photoService.find(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_FOREVER)
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.content());
    }
}
