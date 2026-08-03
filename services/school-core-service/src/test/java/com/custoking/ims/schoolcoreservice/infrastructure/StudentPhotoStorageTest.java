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
    }

    @Test
    void objectKeysRejectUnsafeSchoolFolderTokens() {
        assertThatThrownBy(() -> StudentPhotoStorage.studentPhotoObjectKey("../1", 42L, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("School storage id contains invalid characters");
    }

    @Test
    void normalizesLandscapePhotoToSquarePortrait() throws Exception {
        BufferedImage landscape = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = landscape.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 800, 400);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(landscape, "jpg", input);

        StudentPhotoStorage storage = new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        byte[] normalized = storage.normalizePortrait(input.toByteArray(), "image/jpeg");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));

        assertThat(result.getWidth()).isEqualTo(512);
        assertThat(result.getHeight()).isEqualTo(512);
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
    void cropFocusSelectsTheRequestedSideOfALandscapePhoto() throws Exception {
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

        Color leftCenter = new Color(left.getRGB(256, 256));
        Color rightCenter = new Color(right.getRGB(256, 256));
        assertThat(leftCenter.getRed()).isGreaterThan(leftCenter.getBlue());
        assertThat(rightCenter.getBlue()).isGreaterThan(rightCenter.getRed());
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
