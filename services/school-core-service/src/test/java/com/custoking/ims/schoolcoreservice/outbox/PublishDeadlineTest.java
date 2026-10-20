package com.custoking.ims.schoolcoreservice.outbox;
import com.google.api.core.SettableApiFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PublishDeadlineTest {
    @Test void stalledPublishIsCancelledWithinBound() {
        var future = SettableApiFuture.<String>create(); long started=System.nanoTime();
        assertThatThrownBy(() -> PubSubDomainEventPublisher.awaitPublish(future, 25)).isInstanceOf(IllegalStateException.class).hasMessageContaining("deadline");
        assertThat(future.isCancelled()).isTrue(); assertThat(System.nanoTime()-started).isLessThan(1_000_000_000L);
    }
    @Test void completedPublishWorks() throws Exception {
        var future=SettableApiFuture.<String>create();future.set("message-id");assertThat(PubSubDomainEventPublisher.awaitPublish(future,25)).isEqualTo("message-id");
    }
}
