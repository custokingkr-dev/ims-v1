package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.internal.BroadcastRecipientPolicyController;
import com.custoking.ims.schoolcoreservice.persistence.BroadcastRecipientPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BroadcastRecipientPolicyControllerTest {
    final BroadcastRecipientPolicyRepository repository = mock(BroadcastRecipientPolicyRepository.class);
    final String path = "/api/v1/internal/notifications/broadcast-recipients";
    final UUID id = UUID.randomUUID();
    String body(String category) { return "{\"schoolId\":10,\"broadcastId\":\""+id+"\",\"communicationCategory\":\""+category+"\",\"audienceType\":\"ALL_PARENTS\",\"channels\":[\"SMS\"]}"; }
    @Test void missingCredentialOrUnconfiguredServiceNeverReadsRecipients() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BroadcastRecipientPolicyController(repository,"secret")).build();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body("SCHOOL_NOTICE"))).andExpect(status().isUnauthorized());
        var off = MockMvcBuilders.standaloneSetup(new BroadcastRecipientPolicyController(repository,"")).build();
        off.perform(post(path).header("X-Broadcast-Policy-Token","secret").contentType(MediaType.APPLICATION_JSON).content(body("SCHOOL_NOTICE"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }
    @Test void unsupportedCategoryCannotBorrowSchoolCommunicationConsent() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BroadcastRecipientPolicyController(repository,"secret")).build();
        mvc.perform(post(path).header("X-Broadcast-Policy-Token","secret").contentType(MediaType.APPLICATION_JSON).content(body("MARKETING"))).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void validPeerIsBoundToRequestedSchoolAndAudience() throws Exception {
        when(repository.resolve(10,id,List.of("SMS"),null)).thenReturn(List.of(Map.of("studentId",1,"allowed",false,"reason","CONTACT_NOT_VERIFIED")));
        var mvc = MockMvcBuilders.standaloneSetup(new BroadcastRecipientPolicyController(repository,"secret")).build();
        mvc.perform(post(path).header("X-Broadcast-Policy-Token","secret").contentType(MediaType.APPLICATION_JSON).content(body("SCHOOL_NOTICE"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].allowed").value(false));
        verify(repository).resolve(10,id,List.of("SMS"),null);
    }
}
