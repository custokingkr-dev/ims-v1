package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.PasswordResetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PasswordResetServiceTest {
    private final PasswordResetRepository repository = mock(PasswordResetRepository.class);
    private final PasswordResetMailer mailer = mock(PasswordResetMailer.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthAbuseProtection abuse = mock(AuthAbuseProtection.class);
    private final PasswordResetService service = new PasswordResetService(repository, mailer, encoder, abuse, true);

    @Test void unavailableRecoveryCannotIssueOrConfirmSecrets() {
        assertThatThrownBy(() -> service.request("staff@example.invalid")).hasMessageContaining("503");
        assertThatThrownBy(() -> service.confirm("A".repeat(43), "a-long-password")).hasMessageContaining("503");
        service.deliverPending(); verifyNoInteractions(repository, abuse, encoder);
    }
    @Test void requestAcknowledgesDurableIntentWithoutWaitingForEmailOrExposingEligibility() {
        when(mailer.enabled()).thenReturn(true);
        service.request("staff@example.invalid");
        verify(abuse).resetRequest("staff@example.invalid");
        verify(repository).enqueue("staff@example.invalid");
        verify(mailer, never()).send(anyString(), anyString());
    }
    @Test void byteLengthCannotSilentlyTruncateAnInternationalPassword() {
        when(mailer.enabled()).thenReturn(true);
        assertThatThrownBy(() -> service.confirm("A".repeat(43), "😀".repeat(19))).hasMessageContaining("72 UTF-8 bytes");
        assertThatThrownBy(() -> service.confirm("A".repeat(43), "too-short")).hasMessageContaining("12 characters");
        verifyNoInteractions(repository, encoder);
    }
    @Test void invalidAndUsedTokensShareAnErrorAndAreNeverChanged() {
        when(mailer.enabled()).thenReturn(true);
        assertThatThrownBy(() -> service.confirm("invalid", "a-long-password")).hasMessageContaining("invalid or expired");
        assertThatThrownBy(() -> service.confirm("A".repeat(43), "a-long-password")).hasMessageContaining("invalid or expired");
        verify(repository).confirm("A".repeat(43), "a-long-password", encoder);
    }
    @Test void liveMailerRequiresValidatedConfigurationAndCannotDisableTls() {
        when(mailer.enabled()).thenReturn(true);
        assertThatThrownBy(() -> new PasswordResetService(repository, mailer, encoder, abuse, false))
                .hasMessageContaining("always-allocated worker");
        assertThatThrownBy(() -> new PasswordResetMailer(true, "http://app.example/reset-password", "from@example.invalid", "smtp.example", 587, "user", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetMailer(true, "https://app.example/reset-password#token=foo", "from@example.invalid", "smtp.example", 587, "user", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PasswordResetMailer(false, "", "", "", 587, "", "").enabled()).isFalse();
    }
}
