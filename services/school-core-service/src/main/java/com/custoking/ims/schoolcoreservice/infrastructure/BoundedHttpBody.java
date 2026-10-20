package com.custoking.ims.schoolcoreservice.infrastructure;

/** Bounded subscriber aborts before accumulating an oversized upstream response. */
public final class BoundedHttpBody implements java.net.http.HttpResponse.BodySubscriber<byte[]> {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "bounded-body-deadline"); thread.setDaemon(true); return thread;
    });
    private final long limit;
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
    private java.util.concurrent.Flow.Subscription subscription;
    private java.util.concurrent.ScheduledFuture<?> deadline;
    public BoundedHttpBody(long limit) { this.limit = limit; }
    public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
        subscription = value;
        deadline = DEADLINES.schedule(() -> {
            value.cancel(); result.completeExceptionally(new java.net.http.HttpTimeoutException("Response body timed out"));
        }, 30, java.util.concurrent.TimeUnit.SECONDS);
        result.whenComplete((body, error) -> deadline.cancel(false));
        value.request(1);
    }
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
        long size = bytes.size();
        for (var buffer : buffers) size += buffer.remaining();
        if (size > limit) {
            subscription.cancel();
            result.completeExceptionally(new java.io.IOException("Response exceeds the safe size limit"));
            return;
        }
        for (var buffer : buffers) {
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
}
