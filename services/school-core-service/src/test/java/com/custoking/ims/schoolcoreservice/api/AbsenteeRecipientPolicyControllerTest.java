package com.custoking.ims.schoolcoreservice.api;
import com.custoking.ims.schoolcoreservice.api.internal.AbsenteeRecipientPolicyController;
import com.custoking.ims.schoolcoreservice.persistence.AbsenteeRecipientPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AbsenteeRecipientPolicyControllerTest {
    @Test void missingOrInvalidPeerCredentialCannotReadOwnedQueue() throws Exception {
        var repository=mock(AbsenteeRecipientPolicyRepository.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AbsenteeRecipientPolicyController(repository,"peer")).build();
        var body="{\"schoolId\":10,\"studentId\":1,\"channel\":\"SMS\",\"eventId\":\"school-core:absentee:n\",\"notificationId\":\"n\",\"attendanceDate\":\"2026-10-07\",\"messageSha256\":\""+"a".repeat(64)+"\"}";
        for (String token:List.of("","wrong"))
            mvc.perform(post("/api/v1/internal/notifications/broadcast-recipients/absentees")
                    .header("X-Broadcast-Policy-Token",token).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/notifications/broadcast-recipients/absentees")
                .header("X-Broadcast-Policy-Token","peer").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"channel\":\"SMS\"","\"channel\":\"MARKETING\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
