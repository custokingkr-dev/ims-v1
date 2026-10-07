package com.custoking.ims.platformservice.application;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NotificationSubmissionResultTest {
    final NotificationDeliveryRequest request=new NotificationDeliveryRequest("one","template","SMS","GUARDIAN","one","{}");
    @Test void legacyVoidSuccessAndExceptionBothRemainUnknown() {
        NotificationDeliveryProvider legacy=value->{};
        assertThat(legacy.submit(request).status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        NotificationDeliveryProvider failed=value->{throw new IllegalStateException("sensitive body");};
        assertThat(failed.submit(request).status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        assertThat(failed.submit(request).toString()).doesNotContain("sensitive body","template");
    }
    @Test void acceptanceRequiresIdentifierAndBindsAllRequestFields() {
        assertThatThrownBy(()->NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.ACCEPTED,null,"PROVIDER_REQUEST_ACCEPTED")).isInstanceOf(IllegalArgumentException.class);
        var result=NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.ACCEPTED,"5762846b4f8d285d378b4567","PROVIDER_REQUEST_ACCEPTED");
        assertThat(result.binds(request)).isTrue();
        assertThat(result.binds(new NotificationDeliveryRequest("one","different","SMS","GUARDIAN","one","{}"))).isFalse();
        assertThat(result.binds(new NotificationDeliveryRequest("one","template","SMS","GUARDIAN","two","{}"))).isFalse();
        assertThat(result.binds(new NotificationDeliveryRequest("one","template","SMS","GUARDIAN","one","{\"changed\":true}"))).isFalse();
    }
}
