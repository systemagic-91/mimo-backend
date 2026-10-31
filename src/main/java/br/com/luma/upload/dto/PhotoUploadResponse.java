package br.com.luma.upload.dto;

import java.util.UUID;

public record PhotoUploadResponse(UUID photoId, String photoUrl, int width, int height) {
}
