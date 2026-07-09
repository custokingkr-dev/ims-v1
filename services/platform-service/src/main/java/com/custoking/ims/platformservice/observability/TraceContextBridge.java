package com.custoking.ims.platformservice.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class TraceContextBridge {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final boolean enabled;

    @Autowired
    public TraceContextBridge(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.custoking.ims.platformservice.pubsub");
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.enabled = true;
    }

    private TraceContextBridge() {
        this.tracer = null;
        this.propagator = null;
        this.enabled = false;
    }

    public static TraceContextBridge noop() {
        return new TraceContextBridge();
    }

    public void runInSpan(String spanName, String traceParent, String traceState, Runnable action) {
        callInSpan(spanName, traceParent, traceState, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callInSpan(String spanName, String traceParent, String traceState, Supplier<T> action) {
        if (!enabled || !StringUtils.hasText(traceParent)) {
            return action.get();
        }

        Map<String, String> carrier = new HashMap<>();
        carrier.put("traceparent", traceParent);
        if (StringUtils.hasText(traceState)) {
            carrier.put("tracestate", traceState);
        }

        Context parent = propagator.extract(Context.root(), carrier, GETTER);
        Span span = tracer.spanBuilder(spanName).setParent(parent).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            T result = action.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (RuntimeException | Error ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }
}
