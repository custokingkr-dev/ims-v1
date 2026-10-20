package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.GenericNotificationReportService;
import com.custoking.ims.platformservice.application.NotificationSubmissionResult;
import com.custoking.ims.platformservice.security.IdentityTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GenericNotificationReportControllerTest {
    private static final String PATH="/api/v1/internal/notifications/reports/reconcile";
    private static final String REPORT="r".repeat(40),SHARED="s".repeat(40),PROVIDER="p".repeat(40);
    private final GenericNotificationReportService service=mock(GenericNotificationReportService.class);
    private final IdentityTokenVerifier verifier=token->"signed-purpose".equals(token)
            ?Optional.of("reports@synthetic.iam.gserviceaccount.com"):Optional.of("gateway@synthetic.iam.gserviceaccount.com");
    private MockMvc mvc(boolean enabled,String secret,String allowed) {
        return MockMvcBuilders.standaloneSetup(new GenericNotificationReportController(service,verifier,enabled,secret,
                SHARED,PROVIDER,allowed,"gateway@synthetic.iam.gserviceaccount.com,delivery@synthetic.iam.gserviceaccount.com")).build();
    }
    private MockMvc mvc(){return mvc(true,REPORT,"reports@synthetic.iam.gserviceaccount.com");}
    private String body(){return """
        {"version":1,"schoolId":10,"eventId":"owned-event","requestSha256":"%s","correlationId":"%s",
         "providerRequestId":"5762846b4f8d285d378b4567","status":"DELIVERED","occurredAt":"2026-10-07T12:00:00Z","evidenceSha256":"%s"}
        """.formatted("a".repeat(64),NotificationSubmissionResult.correlationId("owned-event"),"b".repeat(64));}
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer signed-purpose")
                .header("X-Notification-Report-Token",REPORT).content(body);
    }
    @Test void disabledBoundaryIsUnavailableAndNeverInvokesPersistence() throws Exception {
        mvc(false,REPORT,"reports@synthetic.iam.gserviceaccount.com").perform(request(body())).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void requiresDistinctStrongSecretAndDedicatedSignedPurposeIdentity() throws Exception {
        for(String configured:new String[]{"","short",SHARED,PROVIDER})
            mvc(true,configured,"reports@synthetic.iam.gserviceaccount.com").perform(request(body())).andExpect(status().isUnauthorized());
        for(String configured:new String[]{"","gateway@synthetic.iam.gserviceaccount.com","reports@synthetic.iam.gserviceaccount.com,delivery@synthetic.iam.gserviceaccount.com"})
            mvc(true,REPORT,configured).perform(request(body())).andExpect(status().isUnauthorized());
        mvc().perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer signed-purpose")
                .header("X-Notification-Report-Token",SHARED).content(body())).andExpect(status().isUnauthorized());
        mvc().perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Notification-Report-Token",REPORT).content(body())).andExpect(status().isUnauthorized());
        mvc().perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer gateway")
                .header("X-Notification-Report-Token",REPORT).content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void forwardedUserAndQueryCannotCarryReportAuthority() throws Exception {
        for(String header:new String[]{"X-Authenticated-Role","X-Authenticated-School-Id","X-IMS-Principal-Carrier-Token"})
            mvc().perform(request(body()).header(header,"synthetic-forged")).andExpect(status().isForbidden());
        mvc().perform(request(body()).queryParam("schoolId","10")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void strictSchemaRejectsDuplicatesUnknownFieldsInvalidTypesAndRawVendorBodies() throws Exception {
        for(String invalid:new String[]{body().replace("\"version\":1","\"version\":1,\"version\":2"),
                body().replace("\"version\":1","\"version\":4294967297"),body().replace("\"version\":1","\"version\":1.0"),
                body().replace("\"schoolId\":10","\"schoolId\":\"10\""),body().replace("\"schoolId\":10","\"schoolId\":9223372036854775808"),
                body().replace("\"version\":1","\"version\":1,\"destination\":\"synthetic\""),
                body().replace("DELIVERED","delivered"),body().replace("2026-10-07T12:00:00Z","2026-10-07T12:00:00.000000001Z"),
                body()+"{}",
                "{\"requestId\":\"vendor\",\"eventName\":\"delivered\"}"}) {
            assertThatThrownBy(()->GenericNotificationReportController.parseBytes(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void parsedInternalBindingAndCanonicalOffsetsDoNotExposeInputThroughToString() {
        var parsed=GenericNotificationReportController.parseBytes(body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(parsed.schoolId()).isEqualTo(10);assertThat(parsed.eventId()).isEqualTo("owned-event");
        var same=GenericNotificationReportController.parseBytes(body().replace("2026-10-07T12:00:00Z","2026-10-07T17:30:00+05:30").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(same.reportSha256()).isEqualTo(parsed.reportSha256());
        assertThat(parsed.toString()).doesNotContain("owned-event","5762846",parsed.requestSha256(),parsed.evidenceSha256());
    }
}
