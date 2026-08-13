package com.custoking.ims.platformservice.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpBatchFlushTaskDecoratorTest {

    @Test
    void flushesAfterSuccessfulTask() {
        RecordingSpanProcessor processor = new RecordingSpanProcessor();
        AtomicBoolean ran = new AtomicBoolean();

        new GcpOtlpTraceExporterAuthConfig.OtlpBatchFlushTaskDecorator(processor)
                .decorate(() -> ran.set(true))
                .run();

        assertThat(ran).isTrue();
        assertThat(processor.flushes).hasValue(1);
    }

    @Test
    void flushesWhenTaskFailsAndPreservesFailure() {
        RecordingSpanProcessor processor = new RecordingSpanProcessor();
        Runnable decorated = new GcpOtlpTraceExporterAuthConfig.OtlpBatchFlushTaskDecorator(processor)
                .decorate(() -> { throw new IllegalStateException("task failed"); });

        assertThatThrownBy(decorated::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task failed");
        assertThat(processor.flushes).hasValue(1);
    }

    private static final class RecordingSpanProcessor implements SpanProcessor {
        private final AtomicInteger flushes = new AtomicInteger();

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }

        @Override
        public CompletableResultCode forceFlush() {
            flushes.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
    }
}
