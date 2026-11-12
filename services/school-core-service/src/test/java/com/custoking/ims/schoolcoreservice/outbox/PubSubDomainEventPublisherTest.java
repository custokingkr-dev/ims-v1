package com.custoking.ims.schoolcoreservice.outbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A publish that times out has usually not failed: the client has the message and sends it anyway (cancelling the future
 * does not withdraw it, it only loses the outcome). Sending the event again after such a timeout is what turned the first
 * publish on a new instance into duplicate pushes (on dev, order CK-1017: five pushes for two events). These tests hold the
 * publisher to waiting for that earlier publish instead, while still sending again when it really failed (at least once).
 */
class PubSubDomainEventPublisherTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aTimedOutPublishIsNotSentAgainWhileItMayStillComplete() {
        var client = new FakePubSubClient();
        var publisher = new PubSubDomainEventPublisher(JSON, client, 25, 25);

        assertThatThrownBy(() -> publisher.publishWithin(event("school-core:1"), 25))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deadline");
        assertThat(client.last().isCancelled()).as("cancelling would only lose the outcome of a message already on its way").isFalse();

        // The relay retries the row while that publish is still in flight: it waits for it rather than sending a copy.
        assertThatThrownBy(() -> publisher.publishWithin(event("school-core:1"), 25)).isInstanceOf(IllegalStateException.class);
        assertThat(client.sent).hasSize(1);

        client.last().set("message-1");
        publisher.publishWithin(event("school-core:1"), 25);
        assertThat(client.sent).as("the earlier publish completed, so the event is published without a second copy").hasSize(1);
    }

    @Test
    void aPublishThatFailedIsSentAgain() {
        var client = new FakePubSubClient().respond(future -> future.setException(new IllegalStateException("UNAVAILABLE")));
        var publisher = new PubSubDomainEventPublisher(JSON, client, 1000, 1000);

        assertThatThrownBy(() -> publisher.publishWithin(event("school-core:1"), 1000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to publish").hasRootCauseMessage("UNAVAILABLE");

        client.respond(future -> future.set("message-2"));
        publisher.publishWithin(event("school-core:1"), 1000);
        assertThat(client.sent).hasSize(2);
    }

    @Test
    void aTimedOutPublishThatThenFailsIsSentAgain() {
        var client = new FakePubSubClient();
        var publisher = new PubSubDomainEventPublisher(JSON, client, 25, 25);
        assertThatThrownBy(() -> publisher.publishWithin(event("school-core:1"), 25)).isInstanceOf(IllegalStateException.class);
        client.last().setException(new IllegalStateException("DEADLINE_EXCEEDED"));

        client.respond(future -> future.set("message-2"));
        publisher.publishWithin(event("school-core:1"), 25);
        assertThat(client.sent).as("at least once: a publish known to have failed is sent again").hasSize(2);
    }

    @Test
    void eachEventIsTrackedOnItsOwn() {
        var client = new FakePubSubClient();
        var publisher = new PubSubDomainEventPublisher(JSON, client, 25, 25);
        assertThatThrownBy(() -> publisher.publishWithin(event("school-core:1"), 25)).isInstanceOf(IllegalStateException.class);

        client.respond(future -> future.set("message-2"));
        publisher.publishWithin(event("school-core:2"), 25);
        assertThat(client.sent).hasSize(2);
    }

    /** A new instance's first publish opens the connection and fetches credentials, on a CPU that may be throttled. */
    @Test
    void theFirstPublishGetsTheLongerColdStartAllowanceAndLaterOnesTheUsualTimeout() {
        var client = new FakePubSubClient().succeedAfter(300, "message-1");
        var publisher = new PubSubDomainEventPublisher(JSON, client, 50, 10_000);
        assertThat(publisher.publishTimeoutMs()).isEqualTo(10_000);

        publisher.publish(event("school-core:1"));
        assertThat(publisher.publishTimeoutMs()).isEqualTo(50);

        client.succeedAfter(300, "message-2");
        assertThatThrownBy(() -> publisher.publish(event("school-core:2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deadline");
    }

    @Test
    void timeoutsAreBoundedAndTheFirstIsNeverShorterThanTheUsualOne() {
        var publisher = new PubSubDomainEventPublisher(JSON, new FakePubSubClient(), 0, 0);
        assertThat(publisher.publishTimeoutMs()).isEqualTo(1);
        publisher = new PubSubDomainEventPublisher(JSON, new FakePubSubClient(), 5000, 1000);
        assertThat(publisher.publishTimeoutMs()).isEqualTo(5000);
        publisher = new PubSubDomainEventPublisher(JSON, new FakePubSubClient(), 5000, 3_600_000);
        assertThat(publisher.publishTimeoutMs()).isEqualTo(PubSubDomainEventPublisher.MAX_PUBLISH_TIMEOUT_MS);
    }

    static EventEnvelope event(String id) {
        return new EventEnvelope("ims.event-envelope.v1", id, "CatalogOrderUpserted:CK-1017", "catalog-order.upserted.v1", "v1",
                "CatalogOrder", "CK-1017", OffsetDateTime.parse("2026-11-11T13:35:34Z"), 1L, "{\"id\":\"CK-1017\"}");
    }
}
