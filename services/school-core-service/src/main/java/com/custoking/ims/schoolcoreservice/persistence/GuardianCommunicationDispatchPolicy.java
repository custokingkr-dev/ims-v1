package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.absentee.AbsenteeDispatchPolicy;
import com.custoking.ims.schoolcoreservice.absentee.DispatchDecision;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Dispatch-time re-evaluation backed by the same {@link GuardianCommunicationPolicy} the producer
 * used at queue time, so the worker cannot drift from the authoritative policy. Runs inside the
 * worker's school-scoped transaction; the policy's reads on {@code student.*} are RLS-scoped to
 * that school.
 */
@Component
public class GuardianCommunicationDispatchPolicy implements AbsenteeDispatchPolicy {

    private final GuardianCommunicationPolicy policy;

    public GuardianCommunicationDispatchPolicy(JdbcClient jdbc) {
        this.policy = new GuardianCommunicationPolicy(jdbc);
    }

    @Override
    public DispatchDecision evaluate(long schoolId, long studentId, String channel, String sourceEventId) {
        return toDispatchDecision(policy.evaluate(schoolId, studentId, channel), sourceEventId);
    }

    static DispatchDecision toDispatchDecision(GuardianCommunicationPolicy.Decision decision, String sourceEventId) {
        if (!decision.allowed()) {
            return DispatchDecision.denied(decision.reason());
        }
        return DispatchDecision.allowed(
                decision.guardianId(),
                decision.destination(),
                decision.consentEventId(),
                decision.consentNoticeVersion(),
                decision.channel(),
                decision.schoolId(),
                decision.studentId(),
                decision.evaluatedAt(),
                decision.expiresAt(),
                sourceEventId);
    }
}
