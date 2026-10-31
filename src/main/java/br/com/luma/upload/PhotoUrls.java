package br.com.luma.upload;

import java.util.UUID;

public final class PhotoUrls {

    private PhotoUrls() {
    }

    public static String of(UUID photoId) {
        return photoId == null ? null : "/api/v1/photos/" + photoId;
    }
}
