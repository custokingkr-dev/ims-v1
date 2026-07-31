package com.custoking.ims.schoolcoreservice.photoimport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDrivePhotoImportClientTest {

    @Test
    void enablesDriveOnlyWithCompletePersonalOauthCredentials() {
        var configured = client(true, "root-folder", "client-id", "client-secret", "refresh-token");
        var missingRefreshToken = client(true, "root-folder", "client-id", "client-secret", "");

        assertThat(configured.isEnabled()).isTrue();
        assertThat(configured.isProvisioningEnabled()).isTrue();
        assertThat(missingRefreshToken.isEnabled()).isFalse();
        assertThat(missingRefreshToken.isProvisioningEnabled()).isFalse();
    }

    @Test
    void requiresRootFolderInAdditionToPersonalOauthCredentialsForProvisioning() {
        var configuredWithoutRoot = client(true, "", "client-id", "client-secret", "refresh-token");

        assertThat(configuredWithoutRoot.isEnabled()).isTrue();
        assertThat(configuredWithoutRoot.isProvisioningEnabled()).isFalse();
    }

    private static GoogleDrivePhotoImportClient client(
            boolean enabled,
            String rootFolder,
            String clientId,
            String clientSecret,
            String refreshToken) {
        return new GoogleDrivePhotoImportClient(
                new ObjectMapper(),
                enabled,
                rootFolder,
                clientId,
                clientSecret,
                refreshToken);
    }
}
