package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Msg91BroadcastReportControllerTest {
    private static final String PATH = "/api/v1/notifications/provider-reports/msg91/email";
    private static final String SERVICE = "synthetic-service-credential";
    private static final String WEBHOOK = "synthetic-webhook-credential-32-characters";
    private static final String CORRELATION = "ims" + "b".repeat(48);
    private static final String RECEIPT = "a1d2e4b8-4d04-41f9-b8f2-34be6a5c6d49";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void unauthenticatedCallbackNeverOpensItsServletInputStream() throws Exception {
        var f=fixture(true,SERVICE,WEBHOOK);
        for(String[] tokens:new String[][]{{"",WEBHOOK},{SERVICE,""},{"wrong",WEBHOOK},{SERVICE,"wrong"},{WEBHOOK,SERVICE}}) {
            f.mvc.perform(context->{
                var request=new MockHttpServletRequest(context) {
                    @Override public ServletInputStream getInputStream() {
                        throw new AssertionError("Unauthorized callback body was accessed");
                    }
                };
                request.setMethod("POST");request.setRequestURI(PATH);request.setContentType("application/json");
                request.addHeader("X-Notification-Service-Token",tokens[0]);request.addHeader("X-MSG91-Webhook-Token",tokens[1]);
                return request;
            }).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(f.ledger);assertThat(f.transactions.commits).isZero();
    }

    @Test void bothCredentialsAndConfiguredSecretsAreCheckedBeforeBodyMetadataOrAsyncAdmission() throws Exception {
        for(String[] secrets:new String[][]{{SERVICE,WEBHOOK},{"",WEBHOOK},{SERVICE,""},{SERVICE,"short"}}) {
            var f=fixture(true,secrets[0],secrets[1]);
            for(String[] tokens:new String[][]{{"",WEBHOOK},{SERVICE,""},{"wrong",WEBHOOK},{SERVICE,"wrong"},{WEBHOOK,SERVICE}}) {
                var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(()->f.controller.report(tokens[0],tokens[1],request,response))
                        .isInstanceOfSatisfying(ResponseStatusException.class,failure->assertThat(failure.getStatusCode().value()).isEqualTo(401));
                verifyNoInteractions(request,response);
            }
            if(!secrets[0].equals(SERVICE) || !secrets[1].equals(WEBHOOK)) {
                var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(()->f.controller.report(SERVICE,WEBHOOK,request,response))
                        .isInstanceOfSatisfying(ResponseStatusException.class,failure->assertThat(failure.getStatusCode().value()).isEqualTo(401));
                verifyNoInteractions(request,response);
            }
            verifyNoInteractions(f.ledger);assertThat(f.transactions.commits).isZero();
        }
    }

    @Test void bothIndependentCredentialsAreRequiredBeforeParsingOrPersistence() throws Exception {
        var f = fixture(true, SERVICE, WEBHOOK);
        for (String[] tokens : new String[][]{{"", WEBHOOK}, {SERVICE, ""}, {"wrong", WEBHOOK}, {SERVICE, "wrong"}, {WEBHOOK, SERVICE}}) {
            var result = f.mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Notification-Service-Token", tokens[0]).header("X-MSG91-Webhook-Token", tokens[1])
                    .content("{ malformed sensitive@example.invalid"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(result.getResolvedException().getMessage()).doesNotContain("sensitive", SERVICE, WEBHOOK);
        }
        f.mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("DELIVERED"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(f.ledger);
    }

    @Test void missingConfiguredSecretsFailClosed() throws Exception {
        for (String[] config : new String[][]{{"", WEBHOOK}, {SERVICE, "short"}, {SERVICE, ""}}) {
            var f = fixture(true, config[0], config[1]);
            f.mvc.perform(authenticated(body("DELIVERED"))).andExpect(status().isUnauthorized());
            verifyNoInteractions(f.ledger);
        }
    }

    @Test void boundReportPassesOnlyHashesToLedgerInsideTransaction() throws Exception {
        var f = fixture(true, SERVICE, WEBHOOK);
        when(f.ledger.report(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(invocation.getArgument(0, String.class)).isEqualTo(CORRELATION);
            assertThat(invocation.getArgument(1, String.class)).isEqualTo(RECEIPT);
            assertThat(invocation.getArgument(2, String.class)).isEqualTo(sha("guardian@synthetic.invalid"));
            assertThat(invocation.getArgument(3, String.class)).isEqualTo(sha("sender@synthetic.invalid"));
            assertThat(invocation.getArgument(4, String.class)).isEqualTo("DELIVERED");
            assertThat(invocation.getArgument(6, String.class)).matches("[0-9a-f]{64}");
            return true;
        });
        f.mvc.perform(authenticated(body("DELIVERED"))).andExpect(status().isAccepted()).andExpect(content().json("{\"accepted\":true}"));
        assertThat(f.transactions.commits).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void unknownAndKnownBindingHaveSameGeneric202AndKillSwitchStillAcceptsReceipts() throws Exception {
        var f = fixture(false, SERVICE, WEBHOOK);
        when(f.ledger.report(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenReturn(false, true);
        String unknown = f.mvc.perform(authenticated(body("DELIVERED"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String known = f.mvc.perform(authenticated(body("DELIVERED"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(known).isEqualTo("{\"accepted\":true}");
        verify(f.ledger, times(2)).report(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void malformedAndExtraFieldsNeverReachLedgerOrLeakPayload() throws Exception {
        var f = fixture(true, SERVICE, WEBHOOK);
        List<String> invalid = new java.util.ArrayList<>(List.of("{", "null", "[]", "{}", "\"sensitive@synthetic.invalid\""));
        for (Map<String, Object> fields : List.of(
                altered("status", "OPENED"), altered("status", 4), altered("occurredAt", "yesterday"),
                altered("destination", "guardian@synthetic.invalid\r\nBcc:other@example.invalid"),
                altered("sender", "<sender@synthetic.invalid>"), altered("providerMessageId", "secret@synthetic.invalid"),
                altered("correlationId", "invalid"), altered("unexpected", "sensitive@example.invalid"))) invalid.add(mapper.writeValueAsString(fields));
        var missing = fields("DELIVERED"); missing.remove("sender"); invalid.add(mapper.writeValueAsString(missing));
        for (String body : invalid) {
            var result = f.mvc.perform(authenticated(body)).andExpect(status().isBadRequest()).andReturn();
            assertThat(result.getResponse().getErrorMessage()).isEqualTo("Invalid provider report");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive@", "guardian@", SERVICE, WEBHOOK);
        }
        verifyNoInteractions(f.ledger);
    }

    @Test void oversizedReportIsRejectedBeforeParsingAndWrites() throws Exception {
        var f = fixture(true, SERVICE, WEBHOOK);
        var result = f.mvc.perform(authenticated("x".repeat(32_769))).andExpect(status().isPayloadTooLarge()).andReturn();
        assertThat(result.getResolvedException()).hasMessageContaining("Provider report is too large");
        verifyNoInteractions(f.ledger);
    }

    @Test void exactly32768BytesRemainAcceptedAndMalformedUtf8NeverWrites() throws Exception {
        var f=fixture(true,SERVICE,WEBHOOK);
        String valid=body("DELIVERED");
        f.mvc.perform(authenticated(valid+" ".repeat(32768-valid.getBytes(StandardCharsets.UTF_8).length)))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"accepted\":true}"));
        verify(f.ledger).report(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString());
        clearInvocations(f.ledger);
        byte[] bytes=valid.getBytes(StandardCharsets.UTF_8);int offset=valid.indexOf("Guardian");
        bytes[offset]=(byte)0xc3;bytes[offset+1]=(byte)0x28;
        f.mvc.perform(authenticated(bytes)).andExpect(status().isBadRequest());verifyNoInteractions(f.ledger);
        assertThat(f.transactions.commits).isEqualTo(1);
    }

    @Test void utf16AndUtf32ReportsAreRejectedWhileUtf8BomKeepsItsExistingMeaning() throws Exception {
        var f=fixture(true,SERVICE,WEBHOOK);String valid=body("DELIVERED");
        byte[] utf32=valid.getBytes(java.nio.charset.Charset.forName("UTF-32BE"));
        byte[] utf32Bom=java.nio.ByteBuffer.allocate(utf32.length+4).putInt(0xFEFF).put(utf32).array();
        for(byte[] unsupported:List.of(valid.getBytes(StandardCharsets.UTF_16),utf32Bom))
            f.mvc.perform(authenticated(unsupported)).andExpect(status().isBadRequest());
        verifyNoInteractions(f.ledger);assertThat(f.transactions.commits).isZero();
        byte[] utf8=valid.getBytes(StandardCharsets.UTF_8);
        byte[] utf8Bom=java.nio.ByteBuffer.allocate(utf8.length+3).put(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf}).put(utf8).array();
        f.mvc.perform(authenticated(utf8Bom)).andExpect(status().isAccepted()).andExpect(content().json("{\"accepted\":true}"));
        assertThat(f.transactions.commits).isEqualTo(1);
    }

    @Test void duplicateEscapedDuplicateAndTrailingDocumentsNeverReachLedger() throws Exception {
        var f=fixture(true,SERVICE,WEBHOOK);
        String valid=body("DELIVERED");
        for(String invalid:List.of(
                valid.replace("\"status\":\"DELIVERED\"","\"status\":\"FAILED\",\"status\":\"DELIVERED\""),
                valid.replace("\"providerMessageId\":","\"providerMessageId\":\"unconfirmed\",\"providerMessageI\\u0064\":"),
                valid+" {}",valid+" null",valid+" trailing")) {
            f.mvc.perform(authenticated(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(f.ledger);assertThat(f.transactions.commits).isZero();
    }

    @Test void databaseFailureReturnsRetryableGenericErrorWithoutItsSensitiveCause() throws Exception {
        var f = fixture(true, SERVICE, WEBHOOK);
        when(f.ledger.report(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("guardian@synthetic.invalid " + SERVICE + " " + WEBHOOK));
        var result = f.mvc.perform(authenticated(body("DELIVERED"))).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(result.getResponse().getErrorMessage()).isEqualTo("Provider report persistence unavailable");
        assertThat(f.transactions.commits).isZero();
        assertThat(f.transactions.rollbacks).isEqualTo(1);
    }

    @Test void reportHashNormalizesEquivalentAddressCaseAndTimestampsWithoutConfusingAcceptanceAndDelivery() {
        var first = fields("QUEUED");
        var second = fields("ACCEPTED"); second.put("destination", "guardian@synthetic.invalid");
        second.put("sender", "sender@synthetic.invalid"); second.put("occurredAt", "2026-09-26T17:00:00Z");
        var queued = Msg91BroadcastReportController.parse(mapper.valueToTree(first));
        var accepted = Msg91BroadcastReportController.parse(mapper.valueToTree(second));
        assertThat(queued.status()).isEqualTo("ACCEPTED");
        assertThat(queued.hash()).isEqualTo(accepted.hash());
        var delivered = Msg91BroadcastReportController.parse(mapper.valueToTree(fields("DELIVERED")));
        var failed = Msg91BroadcastReportController.parse(mapper.valueToTree(fields("FAILED")));
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(failed.status()).isEqualTo("DELIVERY_FAILED");
        assertThat(delivered.hash()).isNotEqualTo(accepted.hash()).isNotEqualTo(failed.hash());
        assertThat(delivered.toString()).doesNotContain("guardian@", "sender@");
    }

    private String body(String status) { return mapper.writeValueAsString(fields(status)); }
    private Map<String, Object> fields(String status) {
        var data = new LinkedHashMap<String, Object>();
        data.put("correlationId", CORRELATION); data.put("providerMessageId", RECEIPT);
        data.put("destination", " Guardian@Synthetic.Invalid "); data.put("sender", "Sender@Synthetic.Invalid");
        data.put("status", status); data.put("occurredAt", "2026-09-26T22:30:00+05:30"); return data;
    }
    private Map<String, Object> altered(String key, Object value) { var data = fields("DELIVERED"); data.put(key, value); return data; }
    private RequestBuilder authenticated(String body) {return authenticated(body.getBytes(StandardCharsets.UTF_8));}
    private RequestBuilder authenticated(byte[] body) {
        return context->{
            var request=new MockHttpServletRequest(context) {
                private final ServletInputStream input=new ServletInputStream() {
                    private final ByteArrayInputStream bytes=new ByteArrayInputStream(body);
                    @Override public int read(){return bytes.read();}
                    @Override public int read(byte[] target,int offset,int length){return bytes.read(target,offset,length);}
                    @Override public boolean isReady(){return true;}
                    @Override public boolean isFinished(){return bytes.available()==0;}
                    @Override public void setReadListener(ReadListener listener) {
                        try {listener.onDataAvailable();if(isFinished())listener.onAllDataRead();}
                        catch(IOException failure){listener.onError(failure);}
                    }
                };
                @Override public ServletInputStream getInputStream(){return input;}
            };
            request.setMethod("POST");request.setRequestURI(PATH);request.setContentType("application/json");
            request.setContent(body);request.setAsyncSupported(true);
            request.addHeader("X-Notification-Service-Token",SERVICE);request.addHeader("X-MSG91-Webhook-Token",WEBHOOK);
            return request;
        };
    }
    private Fixture fixture(boolean enabled, String service, String webhook) {
        var ledger = mock(BroadcastLiveRepository.class); var manager = new TrackingTransactions();
        var config = new LiveBroadcastConfiguration(enabled, "EMAIL", true, "1", "a".repeat(64), webhook);
        var controller = new Msg91BroadcastReportController(service, config, ledger, manager);
        return new Fixture(MockMvcBuilders.standaloneSetup(controller).build(), ledger, manager,controller);
    }
    private static String sha(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private record Fixture(MockMvc mvc, BroadcastLiveRepository ledger, TrackingTransactions transactions,Msg91BroadcastReportController controller) {}
    private static final class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
