package com.custoking.ims.schoolcoreservice.absentee;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dry-run is the shipped default: it performs every step except the real send and logs what it
 * would have sent as a structured argument (so the logback {@code <arguments/>} provider turns it
 * into a JSON field), without copying the destination or the message text into Cloud Logging.
 */
class DryRunAbsenteeDeliveryGatewayTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DryRunAbsenteeDeliveryGateway.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void returnsDryRunOutcomeAndLogsAStructuredPiiFreeWouldSendLine() {
        DeliveryOutcome outcome = new DryRunAbsenteeDeliveryGateway("approved_absence_v1").deliver(request());

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.DRY_RUN);
        assertThat(outcome.provider()).isEqualTo("dry-run");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).startsWith("absentee.delivery.would_send");
        assertThat(event.getArgumentArray()).anySatisfy(arg -> assertThat(arg).isInstanceOf(StructuredArgument.class));
        String rendered = event.getFormattedMessage();
        assertThat(rendered).contains("notificationId=row-1")
                .contains("eventId=school-core:absentee:row-1")
                .contains("channel=WHATSAPP")
                .contains("templateName=approved_absence_v1")
                .contains("destinationSha256=" + DispatchDecision.destinationSha256("WHATSAPP", "919999999999"))
                .doesNotContain("919999999999")
                .doesNotContain("Dear Parent");
    }

    private static AbsenteeDeliveryRequest request() {
        OffsetDateTime now = OffsetDateTime.now();
        DispatchDecision decision = DispatchDecision.allowed("guardian-1", "919999999999", "consent-1", "notice-v1",
                "WHATSAPP", 10L, 7L, now, now.plusSeconds(90), "school-core:absentee:row-1");
        return new AbsenteeDeliveryRequest("school-core:absentee:row-1", "row-1", 10L, 7L, "WHATSAPP",
                "919999999999", "guardian-1", LocalDate.of(2026, 9, 10),
                "Dear Parent, A was marked absent.", Map.copyOf(decision.evidence()));
    }
}
