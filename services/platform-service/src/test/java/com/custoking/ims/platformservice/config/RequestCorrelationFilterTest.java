package com.custoking.ims.platformservice.config;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    @Test
    void flushesTracesBeforeRequestReturns() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter).build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build();
        RequestCorrelationFilter filter = new RequestCorrelationFilter(processor);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        tracerProvider.get("request-flush-test").spanBuilder("completed-request").startSpan().end();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(exporter.exportedSpans).hasValue(1);
        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isNotBlank();
        tracerProvider.close();
    }

    private static final class RecordingExporter implements SpanExporter {
        private final AtomicInteger exportedSpans = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportedSpans.addAndGet(spans.size());
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
