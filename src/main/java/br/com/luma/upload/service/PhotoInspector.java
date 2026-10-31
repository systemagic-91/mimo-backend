package br.com.luma.upload.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import br.com.luma.shared.exception.ApiException;

@Component
public class PhotoInspector {

    public static final int MAX_DIMENSION = 2000;

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    public PhotoMetadata inspect(byte[] content) {
        PhotoMetadata metadata = detect(content);
        if (metadata == null) {
            throw invalid("Envie uma imagem JPEG, PNG ou WebP válida.");
        }
        if (metadata.width() < 1 || metadata.height() < 1) {
            throw invalid("Não foi possível ler as dimensões da imagem.");
        }
        if (metadata.width() > MAX_DIMENSION || metadata.height() > MAX_DIMENSION) {
            throw invalid("A imagem deve ter no máximo " + MAX_DIMENSION + "x" + MAX_DIMENSION + " px.");
        }
        return metadata;
    }

    private static PhotoMetadata detect(byte[] content) {
        if (content == null) {
            return null;
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return jpeg(content);
        }
        if (startsWith(content, 0, PNG_SIGNATURE)) {
            return png(content);
        }
        if (startsWith(content, 0, ascii("RIFF")) && startsWith(content, 8, ascii("WEBP"))) {
            return webp(content);
        }
        return null;
    }

    private static PhotoMetadata jpeg(byte[] content) {
        int position = 2;
        while (position + 3 < content.length) {
            if ((content[position] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = content[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                position += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return null;
            }
            int length = uint16BigEndian(content, position + 2);
            if (length < 2) {
                return null;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                if (position + 9 > content.length) {
                    return null;
                }
                int height = uint16BigEndian(content, position + 5);
                int width = uint16BigEndian(content, position + 7);
                return new PhotoMetadata("image/jpeg", width, height);
            }
            position += 2 + length;
        }
        return null;
    }

    private static PhotoMetadata png(byte[] content) {
        if (content.length < 24 || !startsWith(content, 12, ascii("IHDR"))) {
            return null;
        }
        return new PhotoMetadata("image/png", int32BigEndian(content, 16), int32BigEndian(content, 20));
    }

    private static PhotoMetadata webp(byte[] content) {
        if (content.length < 30) {
            return null;
        }
        if (startsWith(content, 12, ascii("VP8 "))) {
            if (!startsWith(content, 23, 0x9D, 0x01, 0x2A)) {
                return null;
            }
            return new PhotoMetadata("image/webp",
                    uint16LittleEndian(content, 26) & 0x3FFF, uint16LittleEndian(content, 28) & 0x3FFF);
        }
        if (startsWith(content, 12, ascii("VP8L"))) {
            if ((content[20] & 0xFF) != 0x2F) {
                return null;
            }
            int bits = (content[21] & 0xFF) | (content[22] & 0xFF) << 8 | (content[23] & 0xFF) << 16
                    | (content[24] & 0xFF) << 24;
            return new PhotoMetadata("image/webp", (bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1);
        }
        if (startsWith(content, 12, ascii("VP8X"))) {
            return new PhotoMetadata("image/webp", uint24LittleEndian(content, 24) + 1,
                    uint24LittleEndian(content, 27) + 1);
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, int... expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((content[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] content, int offset, byte[] expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (content[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int uint16BigEndian(byte[] content, int offset) {
        return (content[offset] & 0xFF) << 8 | (content[offset + 1] & 0xFF);
    }

    private static int uint16LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xFF) | (content[offset + 1] & 0xFF) << 8;
    }

    private static int uint24LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xFF) | (content[offset + 1] & 0xFF) << 8 | (content[offset + 2] & 0xFF) << 16;
    }

    private static int int32BigEndian(byte[] content, int offset) {
        long value = (long) (content[offset] & 0xFF) << 24 | (content[offset + 1] & 0xFF) << 16
                | (content[offset + 2] & 0xFF) << 8 | (content[offset + 3] & 0xFF);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message,
                Map.of("file", List.of(message)));
    }
}
