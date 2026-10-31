package br.com.luma.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import br.com.luma.shared.exception.ApiException;

class PhotoInspectorTests {

    private final PhotoInspector inspector = new PhotoInspector();

    @Test
    void detectsJpegByItsBytes() throws IOException {
        PhotoMetadata metadata = inspector.inspect(image("jpg", 320, 240));

        assertThat(metadata).isEqualTo(new PhotoMetadata("image/jpeg", 320, 240));
    }

    @Test
    void detectsPngByItsBytes() throws IOException {
        PhotoMetadata metadata = inspector.inspect(image("png", 200, 150));

        assertThat(metadata).isEqualTo(new PhotoMetadata("image/png", 200, 150));
    }

    @Test
    void detectsLossyWebp() {
        byte[] webp = webp("VP8 ");
        webp[23] = (byte) 0x9D;
        webp[24] = 0x01;
        webp[25] = 0x2A;
        webp[26] = (byte) 320;
        webp[27] = (byte) (320 >> 8);
        webp[28] = (byte) 240;
        webp[29] = 0;

        assertThat(inspector.inspect(webp)).isEqualTo(new PhotoMetadata("image/webp", 320, 240));
    }

    @Test
    void detectsLosslessWebp() {
        byte[] webp = webp("VP8L");
        int bits = (640 - 1) | (480 - 1) << 14;
        webp[20] = 0x2F;
        webp[21] = (byte) bits;
        webp[22] = (byte) (bits >> 8);
        webp[23] = (byte) (bits >> 16);
        webp[24] = (byte) (bits >> 24);

        assertThat(inspector.inspect(webp)).isEqualTo(new PhotoMetadata("image/webp", 640, 480));
    }

    @Test
    void detectsExtendedWebp() {
        byte[] webp = webp("VP8X");
        webp[24] = (byte) (1999 & 0xFF);
        webp[25] = (byte) (1999 >> 8);
        webp[27] = (byte) (99 & 0xFF);

        assertThat(inspector.inspect(webp)).isEqualTo(new PhotoMetadata("image/webp", 2000, 100));
    }

    @Test
    void rejectsSvgEvenWhenItLooksLikeAnImage() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertInvalid(svg);
    }

    @Test
    void rejectsUnsupportedOrTruncatedFormats() throws IOException {
        assertInvalid("GIF89a......".getBytes(StandardCharsets.US_ASCII));
        assertInvalid(new byte[0]);
        byte[] png = image("png", 10, 10);
        assertInvalid(Arrays.copyOf(png, 20));
        byte[] jpeg = image("jpg", 10, 10);
        assertInvalid(Arrays.copyOf(jpeg, 4));
    }

    @Test
    void rejectsImagesLargerThanTheMaximumDimensions() throws IOException {
        assertInvalid(image("png", 2001, 1));
        assertInvalid(image("jpg", 1, 2001));
        assertThat(inspector.inspect(image("png", 2000, 2000)).width()).isEqualTo(2000);
    }

    @Test
    void rejectsZeroDimensions() {
        byte[] webp = webp("VP8 ");
        webp[23] = (byte) 0x9D;
        webp[24] = 0x01;
        webp[25] = 0x2A;

        assertInvalid(webp);
    }

    private void assertInvalid(byte[] content) {
        assertThatThrownBy(() -> inspector.inspect(content)).isInstanceOfSatisfying(ApiException.class,
                exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.getFields()).containsKey("file");
                });
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private static byte[] webp(String chunk) {
        byte[] content = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);
        System.arraycopy(chunk.getBytes(StandardCharsets.US_ASCII), 0, content, 12, 4);
        return content;
    }
}
