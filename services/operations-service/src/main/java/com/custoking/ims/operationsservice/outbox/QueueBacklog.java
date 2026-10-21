package com.custoking.ims.operationsservice.outbox;

/**
 * Another durable retry queue whose backlog counts toward this service's outbox health figures, so the existing outbox
 * pending, oldest-age and dead-letter log metrics and their alerts cover it too.
 */
public interface QueueBacklog {

    /** Rows still being retried, rows given up on, and how long the oldest row still being retried has waited. */
    record Backlog(long pendingCount, long deadLetterCount, long oldestPendingAgeSeconds) {
        /** Both queues together: the counts add up and the oldest wait is the longer one. */
        public Backlog plus(Backlog other) {
            return new Backlog(pendingCount + other.pendingCount, deadLetterCount + other.deadLetterCount,
                    Math.max(oldestPendingAgeSeconds, other.oldestPendingAgeSeconds));
        }
    }

    Backlog backlog();

    /** The queue's name in the health log when it cannot be counted; a proxied bean's class name would not say. */
    default String queueName() {
        return getClass().getSimpleName();
    }
}
