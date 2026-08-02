package com.custoking.ims.schoolcoreservice.photoimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriveMappingRulesTest {
    @Test
    void acceptsRawAndGoogleDriveFolderLinks() {
        assertThat(DriveFolderId.parse("1AbCdEfGhij_234567"))
                .isEqualTo("1AbCdEfGhij_234567");
        assertThat(DriveFolderId.parse("https://drive.google.com/drive/folders/1AbCdEfGhij_234567?usp=sharing"))
                .isEqualTo("1AbCdEfGhij_234567");
    }

    @Test
    void rejectsNonGoogleAndNonFolderLinks() {
        assertThatThrownBy(() -> DriveFolderId.parse("https://example.com/folders/1AbCdEfGhij_234567"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriveFolderId.parse("https://drive.google.com/file/d/1AbCdEfGhij_234567"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsOnlyDscImageNumbersCaseInsensitively() {
        assertThat(DscImageNumber.fromFileName("DSC5236.jpg")).contains("5236");
        assertThat(DscImageNumber.fromFileName("dsc_005478.JPG")).contains("5478");
        assertThat(DscImageNumber.fromFileName("_DSC4521.jpg")).contains("4521");
        assertThat(DscImageNumber.fromFileName("_DSC_004521.jpeg")).contains("4521");
        assertThat(DscImageNumber.fromFileName("DSC5236.webp")).contains("5236");
        assertThat(DscImageNumber.fromFileName("IMG5236.jpg")).isEmpty();
        assertThat(DscImageNumber.fromFileName("DSC5236 copy.jpg")).isEmpty();
    }
}
