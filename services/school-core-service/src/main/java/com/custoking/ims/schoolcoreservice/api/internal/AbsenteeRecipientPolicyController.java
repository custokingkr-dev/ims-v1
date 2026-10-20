package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeRecipientPolicyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Cloud Run IAM plus the existing purpose-scoped notification-policy peer credential. */
@RestController
@RequestMapping("/api/v1/internal/notifications/broadcast-recipients/absentees")
public class AbsenteeRecipientPolicyController {
    private final AbsenteeRecipientPolicyRepository repository;
    private final String token;
    public AbsenteeRecipientPolicyController(AbsenteeRecipientPolicyRepository repository,
            @Value("${notification.broadcast.policy-token:}") String token) {
        this.repository=repository; this.token=token==null ? "" : token.trim();
    }
    @PostMapping
    public List<Map<String,Object>> resolve(@RequestHeader(value="X-Broadcast-Policy-Token",required=false) String supplied,
            @Valid @RequestBody Request body) {
        requireToken(supplied,"notification:absentee-policy");
        try { return repository.resolve(body.schoolId(),body.studentId(),body.channel(),body.eventId(),
                    body.notificationId(),body.attendanceDate(),body.messageSha256()); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid bounded absentee request"); }
    }
    private void requireToken(String supplied, String scope) {
        if (!"notification:absentee-policy".equals(scope))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid internal route scope");
        if (token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid absentee policy credential");
    }
    public record Request(@Positive long schoolId,@Positive long studentId,
            @NotBlank @Pattern(regexp="SMS|WHATSAPP|EMAIL") String channel,
            @NotBlank @Size(max=280) String eventId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9:_-]{1,255}") String notificationId,
            @NotNull LocalDate attendanceDate,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String messageSha256) {}
}
