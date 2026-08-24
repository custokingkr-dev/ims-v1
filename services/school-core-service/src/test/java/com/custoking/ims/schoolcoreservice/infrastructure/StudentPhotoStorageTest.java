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
