package com.custoking.ims.schoolcoreservice.infrastructure;

/** Admission before expensive decoders/parsers: no unbounded waiter queue or aggregate decodes. */
public final class MediaWorkBudget implements AutoCloseable {
    private static final java.util.concurrent.Semaphore SLOTS = new java.util.concurrent.Semaphore(2);
    private MediaWorkBudget() {}
    public static MediaWorkBudget acquire() {
        if (!SLOTS.tryAcquire()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "File processing is busy; retry later");
        return new MediaWorkBudget();
    }
    public void close() { SLOTS.release(); }
}
