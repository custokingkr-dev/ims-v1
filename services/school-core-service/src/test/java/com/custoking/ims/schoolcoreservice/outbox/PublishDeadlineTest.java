package com.custoking.ims.schoolcoreservice.outbox;
import com.google.api.core.SettableApiFuture;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
class PublishDeadlineTest {
    /** The wait is bounded, and the publish is left to finish: the client sends the message anyway, so cancelling loses only its outcome. */
    @Test void stalledPublishTimesOutWithinBoundAndIsLeftToComplete() {
        var future = SettableApiFuture.<String>create(); long started=System.nanoTime();
        assertThatThrownBy(() -> PubSubDomainEventPublisher.awaitPublish(future, 25)).isInstanceOf(TimeoutException.class);
        assertThat(future.isCancelled()).isFalse(); assertThat(System.nanoTime()-started).isLessThan(1_000_000_000L);
    }
    @Test void completedPublishWorks() throws Exception {
        var future=SettableApiFuture.<String>create();future.set("message-id");assertThat(PubSubDomainEventPublisher.awaitPublish(future,25)).isEqualTo("message-id");
    }
}
