package com.nexusfin.gateway.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareBaggageThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Micrometer's ObservationAwareBaggageThreadLocalAccessor so Reactor WebFlux
 * automatically propagates trace baggage (such as X-Correlation-Id) and MDC values
 * across Netty event-loop thread boundaries.
 */
@Configuration
public class ObservabilityConfig {

    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    @Autowired
    public ObservabilityConfig(@Autowired(required = false) ObservationRegistry observationRegistry,
                               @Autowired(required = false) Tracer tracer) {
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
    }

    @PostConstruct
    void registerBaggageContextPropagation() {
        if (observationRegistry != null && tracer != null) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    new ObservationAwareBaggageThreadLocalAccessor(observationRegistry, tracer));
        }
    }
}
