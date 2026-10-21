package com.custoking.ims.schoolcoreservice.infrastructure;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Image files for upload tests, written by the JDK's own encoders. */
public final class TestImages {
    private TestImages() { }

    /** A noisy RGB picture, so compressed files are large enough to cut in the middle of their image data. */
    public static BufferedImage picture(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) image.setRGB(x, y, x * 4099 + y * 131);
        return image;
    }

    public static byte[] png(BufferedImage image) {
        var output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", output);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        return output.toByteArray();
    }

    /** A baseline JPEG, or a progressive one. */
    public static byte[] jpeg(BufferedImage image, boolean progressive) {
        return writeJpeg(new IIOImage(image, null, null), progressive);
    }

    /** A four-component JPEG with an Adobe marker saying CMYK: valid, as print-shop artwork often is. */
    public static byte[] cmykJpeg() {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 64, 64, 4, null);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) raster.setPixel(x, y, new int[]{x * 4, y * 4, 30, 10});
        byte[] plain = writeJpeg(new IIOImage(raster, null, null), false);
        byte[] adobe = {(byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0};
        return insertAt(plain, 2, adobe);
    }

    /** A valid two-component JPEG, which the JDK decodes into no image type it knows. */
    public static byte[] twoComponentJpeg() {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 16, 16, 2, null);
        return writeJpeg(new IIOImage(raster, null, null), false);
    }

    /** The JPEG with an embedded colour profile the reader cannot parse and ignores: its pixels are intact. */
    public static byte[] withInvalidColourProfile(byte[] jpeg) {
        byte[] id = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
        byte[] profile = new byte[200];
        java.util.Arrays.fill(profile, (byte) 7);
        int length = 2 + id.length + 2 + profile.length;
        var segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE2);
        segment.write(length >> 8);
        segment.write(length & 0xFF);
        segment.writeBytes(id);
        segment.write(1);
        segment.write(1);
        segment.writeBytes(profile);
        // After the JFIF segment, which must follow the start-of-image marker directly.
        int at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        return insertAt(jpeg, at, segment.toByteArray());
    }

    private static byte[] writeJpeg(IIOImage content, boolean progressive) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        if (progressive) param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        var output = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.write(null, content, param);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static byte[] insertAt(byte[] jpeg, int at, byte[] segment) {
        byte[] result = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, result, 0, at);
        System.arraycopy(segment, 0, result, at, segment.length);
        System.arraycopy(jpeg, at, result, at + segment.length, jpeg.length - at);
        return result;
    }
}
