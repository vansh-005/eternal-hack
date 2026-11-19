package com.nocook.service.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Manual spans (OBSERVABILITY.md section 4): {@code deadline.process}, {@code outbox.dispatch}, {@code mqtt.publish}.
 * A span can be parented on a W3C {@code traceparent} string, which is how an outbox row (written inside the consume
 * trace) is dispatched later on another thread and still lands in the same trace. Without a tracer everything runs
 * un-instrumented.
 */
@Component
public class Spans {

    private static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<Propagator> propagatorProvider;

    public Spans(ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        this.tracerProvider = tracerProvider;
        this.propagatorProvider = propagatorProvider;
    }

    /** The active span as a traceparent value, or null when no span is active. */
    public @Nullable String currentTraceparent() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }

    /** Runs {@code body} inside a new span (child of {@code parentTraceparent} when given, else of the current span). */
    public <T> T inSpan(String name, @Nullable String parentTraceparent, Map<String, String> attributes, Supplier<T> body) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return body.get();
        }
        Span span = start(tracer, name, parentTraceparent);
        attributes.forEach(span::tag);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return body.get();
        } catch (RuntimeException ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    public void inSpan(String name, @Nullable String parentTraceparent, Map<String, String> attributes, Runnable body) {
        inSpan(name, parentTraceparent, attributes, () -> {
            body.run();
            return null;
        });
    }

    private Span start(Tracer tracer, String name, @Nullable String parentTraceparent) {
        Propagator propagator = propagatorProvider.getIfAvailable();
        if (parentTraceparent != null && propagator != null) {
            return propagator.extract(Map.of(TRACEPARENT, parentTraceparent), Map<String, String>::get)
                    .name(name)
                    .start();
        }
        return tracer.nextSpan().name(name).start();
    }
}
