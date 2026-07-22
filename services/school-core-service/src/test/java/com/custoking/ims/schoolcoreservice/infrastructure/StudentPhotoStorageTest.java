package com.custoking.ims.schoolcoreservice.infrastructure;

import org.junit.jupiter.api.Test;

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
}
