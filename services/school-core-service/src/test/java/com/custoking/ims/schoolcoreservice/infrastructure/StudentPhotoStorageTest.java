package com.custoking.ims.schoolcoreservice.infrastructure;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentPhotoStorageTest {

    @Test
    void nonJpegDecoderReceivesIgnoreMetadataAndNeverRequestsOptionalMetadata() throws Exception {
        var registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
        var builtin = ImageIO.getImageReadersByFormatName("png").next();
        var provider = builtin.getOriginatingProvider(); builtin.dispose();
        var ignoredReads = new java.util.concurrent.atomic.AtomicInteger();
        var metadataRequests = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new javax.imageio.spi.ImageReaderSpi(
                "test", "1", new String[] {"png"}, new String[] {"png"}, new String[] {"image/png"},
                javax.imageio.ImageReader.class.getName(), new Class<?>[] {javax.imageio.stream.ImageInputStream.class},
                null, false, null, null, null, null, false, null, null, null, null) {
            public boolean canDecodeInput(Object input) throws java.io.IOException { return provider.canDecodeInput(input); }
            public String getDescription(java.util.Locale locale) { return "Scoped ordinary PNG decoder contract"; }
            public javax.imageio.ImageReader createReaderInstance(Object extension) throws java.io.IOException {
                var delegate = provider.createReaderInstance();
                return new javax.imageio.ImageReader(this) {
                    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
                        super.setInput(input, seekForwardOnly, ignoreMetadata);
                        delegate.setInput(input, seekForwardOnly, ignoreMetadata);
                    }
                    public int getNumImages(boolean search) throws java.io.IOException { return delegate.getNumImages(search); }
                    public int getWidth(int index) throws java.io.IOException { return delegate.getWidth(index); }
                    public int getHeight(int index) throws java.io.IOException { return delegate.getHeight(index); }
                    public java.util.Iterator<javax.imageio.ImageTypeSpecifier> getImageTypes(int index) throws java.io.IOException { return delegate.getImageTypes(index); }
                    public javax.imageio.metadata.IIOMetadata getStreamMetadata() { throw new IllegalStateException("Optional metadata forbidden"); }
                    public javax.imageio.metadata.IIOMetadata getImageMetadata(int index) {
                        metadataRequests.incrementAndGet(); throw new IllegalStateException("Optional metadata forbidden");
                    }
                    public BufferedImage read(int index, javax.imageio.ImageReadParam param) throws java.io.IOException {
                        if (!isIgnoringMetadata()) throw new IllegalArgumentException("Pixel decode requires ignoreMetadata");
                        ignoredReads.incrementAndGet(); return delegate.read(index, param);
                    }
                    public void dispose() { delegate.dispose(); super.dispose(); }
                };
            }
        };
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); ImageIO.write(image, "png", encoded);
        registry.registerServiceProvider(guarded);
        try {
            registry.setOrdering(javax.imageio.spi.ImageReaderSpi.class, guarded, provider);
            var selected = ImageIO.getImageReadersByFormatName("png").next();
            try { assertThat(selected.getOriginatingProvider()).isSameAs(guarded); } finally { selected.dispose(); }
            // The exact former production decoder fails this benign codec contract.
            assertThatThrownBy(() -> net.coobird.thumbnailator.Thumbnails
                    .of(new ByteArrayInputStream(encoded.toByteArray())).useExifOrientation(true).scale(1).asBufferedImage())
                    .isInstanceOf(RuntimeException.class);
            int oldMetadataRequests = metadataRequests.get();
            assertThat(oldMetadataRequests).isPositive();
            var storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
            assertThat(storage.normalizePortrait(encoded.toByteArray(), "image/png")).isNotEmpty();
            assertThat(ignoredReads.get()).isEqualTo(1);
            assertThat(metadataRequests.get()).isEqualTo(oldMetadataRequests);
        } finally { registry.deregisterServiceProvider(guarded); }
    }

    @Test
    void pngOptionalTextDoesNotChangeNormalizedPixelsEvenWithClaimedJpegType() throws Exception {
        BufferedImage image = new BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB);
        image.setRGB(10, 10, Color.RED.getRGB());
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(image, "png", plain);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream decorated = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(decorated)) {
            writer.setOutput(output);
            var metadata = writer.getDefaultImageMetadata(
                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), writer.getDefaultWriteParam());
            var root = new javax.imageio.metadata.IIOMetadataNode("javax_imageio_png_1.0");
            var text = new javax.imageio.metadata.IIOMetadataNode("tEXt");
            var entry = new javax.imageio.metadata.IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Description");
            entry.setAttribute("value", "Ordinary optional portrait description");
            text.appendChild(entry); root.appendChild(text);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.write(null, new javax.imageio.IIOImage(image, null, metadata), null);
        } finally { writer.dispose(); }
        var storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        assertThat(storage.normalizePortrait(decorated.toByteArray(), "image/jpeg"))
                .isEqualTo(storage.normalizePortrait(plain.toByteArray(), "image/png"));
    }

    @Test
    void detectedJpegPreservesAllEightExifOrientationsDespiteClaimedPngType() throws Exception {
        BufferedImage image = new BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, 24, 12);
        graphics.setColor(Color.GREEN); graphics.fillRect(24, 0, 24, 12);
        graphics.setColor(Color.BLUE); graphics.fillRect(0, 12, 24, 12);
        graphics.setColor(Color.WHITE); graphics.fillRect(24, 12, 24, 12); graphics.dispose();
        ByteArrayOutputStream base = new ByteArrayOutputStream(); ImageIO.write(image, "jpg", base);
        var storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        for (int orientation = 1; orientation <= 8; orientation++) {
            // Small ordinary EXIF APP1: little-endian TIFF with a single orientation entry.
            byte[] exif = java.util.HexFormat.of().parseHex(
                    "45786966000049492a0008000000010012010300010000000000000000000000");
            exif[24] = (byte) orientation;
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            byte[] jpeg = base.toByteArray(); encoded.write(jpeg, 0, 2);
            encoded.write(0xff); encoded.write(0xe1);
            encoded.write((exif.length + 2) >>> 8); encoded.write((exif.length + 2) & 255);
            encoded.write(exif); encoded.write(jpeg, 2, jpeg.length - 2);
            var oriented = net.coobird.thumbnailator.Thumbnails.of(new ByteArrayInputStream(encoded.toByteArray()))
                    .useExifOrientation(true).scale(1).asBufferedImage();
            assertThat(oriented.getWidth()).isEqualTo(orientation >= 5 ? 24 : 48);
            assertThat(oriented.getHeight()).isEqualTo(orientation >= 5 ? 48 : 24);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            net.coobird.thumbnailator.Thumbnails.of(oriented).size(512, 512)
                    .outputFormat("jpg").outputQuality(0.82).toOutputStream(expected);
            assertThat(storage.normalizePortrait(encoded.toByteArray(), "image/png"))
                    .as("EXIF orientation %s", orientation).isEqualTo(expected.toByteArray());
        }
    }

    @Test
    void opaqueNonImageInputFailsWithExistingReadError() {
        var storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        assertThatThrownBy(() -> storage.normalizePortrait(new byte[] {1, 2, 3, 4}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Could not read the image; upload a valid JPG, PNG, or WEBP");
    }

    @Test
    void objectKeysUseSingleSchoolUidFolder() {
        String schoolUid = "11111111-1111-4111-8111-111111111111";
        byte[] data = "bytes".getBytes(StandardCharsets.UTF_8);

        assertThat(StudentPhotoStorage.studentPhotoObjectKey(schoolUid, 42L, data))
                .startsWith("schools/" + schoolUid + "/students/42/photos/")
                .endsWith(".jpg");
        assertThat(StudentPhotoStorage.importFileObjectKey(schoolUid, "batch-1", data, "students import.xlsx"))
                .startsWith("schools/" + schoolUid + "/student-imports/batch-1/")
                .endsWith("-students_import.xlsx");
        assertThat(StudentPhotoStorage.temporaryPhotoImportObjectKey(
                schoolUid, "photo-import-batch-1", data, "_DSC4521.jpeg"))
                .startsWith("temporary/photo-imports/" + schoolUid + "/photo-import-batch-1/")
                .endsWith("-_DSC4521.jpeg");
    }

    @Test
    void temporaryLifecyclePrefixCannotMatchPermanentStudentPhotos() {
        String schoolUid = "11111111-1111-4111-8111-111111111111";
        byte[] data = "bytes".getBytes(StandardCharsets.UTF_8);

        String permanent = StudentPhotoStorage.studentPhotoObjectKey(schoolUid, 42L, data);
        String temporary = StudentPhotoStorage.temporaryPhotoImportObjectKey(
                schoolUid, "photo-import-batch-1", data, "photo.jpg");

        assertThat(permanent).doesNotStartWith("temporary/photo-imports/");
        assertThat(temporary).startsWith("temporary/photo-imports/");
    }

    @Test
    void objectKeysRejectUnsafeSchoolFolderTokens() {
        assertThatThrownBy(() -> StudentPhotoStorage.studentPhotoObjectKey("../1", 42L, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("School storage id contains invalid characters");
    }

    @Test
    void normalizesLandscapePhotoWithoutCroppingTheFrame() throws Exception {
        BufferedImage landscape = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = landscape.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 200, 400);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(200, 0, 400, 400);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(600, 0, 200, 400);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(landscape, "png", input);

        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        byte[] normalized = storage.normalizePortrait(input.toByteArray(), "image/png");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));

        assertThat(result.getWidth()).isEqualTo(512);
        assertThat(result.getHeight()).isEqualTo(256);
        assertThat(new Color(result.getRGB(16, 128)).getRed()).isGreaterThan(200);
        assertThat(new Color(result.getRGB(495, 128)).getBlue()).isGreaterThan(200);
    }

    @Test
    void normalizesPortraitWithoutCroppingTheTopOrBottom() throws Exception {
        BufferedImage portrait = new BufferedImage(400, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = portrait.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 400, 200);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 200, 400, 400);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 600, 400, 200);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(portrait, "png", input);

        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        byte[] normalized = storage.normalizePortrait(input.toByteArray(), "image/png");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));

        assertThat(result.getWidth()).isEqualTo(256);
        assertThat(result.getHeight()).isEqualTo(512);
        assertThat(new Color(result.getRGB(128, 16)).getRed()).isGreaterThan(200);
        assertThat(new Color(result.getRGB(128, 495)).getBlue()).isGreaterThan(200);
    }

    @Test
    void importNormalizationCanUseHigherSourceLimitThanStandardUploadLimit() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", input);

        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 128, 10, "");

        assertThatThrownBy(() -> storage.normalizePortrait(input.toByteArray(), "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Photo must be")
                .hasMessageContaining("or smaller");
        byte[] normalized = storage.normalizePortrait(
                input.toByteArray(), "image/jpeg", 0.5, 0.5, 1024 * 1024);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(result.getWidth()).isEqualTo(128);
        assertThat(result.getHeight()).isEqualTo(128);
    }

    @Test
    void legacyCropFocusCannotDiscardEitherSideOfALandscapePhoto() throws Exception {
        BufferedImage landscape = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = landscape.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 400, 400);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(400, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(landscape, "png", input);

        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        BufferedImage left = ImageIO.read(new ByteArrayInputStream(
                storage.normalizePortrait(input.toByteArray(), "image/png", 0, 0.5)));
        BufferedImage right = ImageIO.read(new ByteArrayInputStream(
                storage.normalizePortrait(input.toByteArray(), "image/png", 1, 0.5)));

        assertThat(left.getWidth()).isEqualTo(512);
        assertThat(left.getHeight()).isEqualTo(256);
        assertThat(right.getWidth()).isEqualTo(512);
        assertThat(right.getHeight()).isEqualTo(256);
        assertThat(new Color(left.getRGB(16, 128)).getRed()).isGreaterThan(200);
        assertThat(new Color(left.getRGB(495, 128)).getBlue()).isGreaterThan(200);
        assertThat(new Color(right.getRGB(16, 128)).getRed()).isGreaterThan(200);
        assertThat(new Color(right.getRGB(495, 128)).getBlue()).isGreaterThan(200);
    }

    @Test
    void rejectsCropFocusOutsideNormalizedRange() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(image, "png", input);
        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");

        assertThatThrownBy(() -> storage.normalizePortrait(
                input.toByteArray(), "image/png", -0.01, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cropX");
        assertThatThrownBy(() -> storage.normalizePortrait(
                input.toByteArray(), "image/png", 0.5, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cropY");
    }
}
