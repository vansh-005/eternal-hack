package com.nocook.service.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Current trace id for persisting on cases and case events; null when no span is active (always acceptable). */
@Component
public class TraceIds {

    private final ObjectProvider<Tracer> tracerProvider;

    public TraceIds(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    public @Nullable String current() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        return span.context().traceId();
    }
}
