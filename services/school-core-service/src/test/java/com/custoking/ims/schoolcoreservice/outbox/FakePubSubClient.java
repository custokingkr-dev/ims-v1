package com.custoking.ims.schoolcoreservice.outbox;

import com.google.api.core.ApiFuture;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.pubsub.v1.PublisherInterface;
import com.google.pubsub.v1.PubsubMessage;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stands in for the Pub/Sub client: records every message it is asked to send and answers each with a future that stays
 * pending until the test settles it, unless a response was queued for that send with {@link #respond}.
 */
final class FakePubSubClient implements PublisherInterface {
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "fake-pubsub");
        thread.setDaemon(true);
        return thread;
    });

    final List<PubsubMessage> sent = new CopyOnWriteArrayList<>();
    final List<SettableApiFuture<String>> futures = new CopyOnWriteArrayList<>();
    private final Queue<Consumer<SettableApiFuture<String>>> responses = new ConcurrentLinkedQueue<>();

    @Override
    public ApiFuture<String> publish(PubsubMessage message) {
        sent.add(message);
        SettableApiFuture<String> future = SettableApiFuture.create();
        futures.add(future);
        Consumer<SettableApiFuture<String>> response = responses.poll();
        if (response != null) response.accept(future);
        return future;
    }

    /** How the next send is answered. */
    FakePubSubClient respond(Consumer<SettableApiFuture<String>> response) {
        responses.add(response);
        return this;
    }

    /** The next send succeeds, but only after {@code delayMs}, as a first publish on a cold connection does. */
    FakePubSubClient succeedAfter(long delayMs, String messageId) {
        return respond(future -> LATER.schedule(() -> future.set(messageId), delayMs, TimeUnit.MILLISECONDS));
    }

    SettableApiFuture<String> last() {
        return futures.getLast();
    }
}
