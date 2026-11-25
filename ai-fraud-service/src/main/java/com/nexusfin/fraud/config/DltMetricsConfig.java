package com.nexusfin.fraud.config;

import com.nexusfin.fraud.service.DltMessageStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes Prometheus / Micrometer gauge for currently quarantined DLT messages.
 */
@Configuration
@RequiredArgsConstructor
public class DltMetricsConfig {

    private final DltMessageStore dltMessageStore;

    @Bean
    public Gauge dltQuarantinedActiveGauge(MeterRegistry meterRegistry) {
        return Gauge.builder("dlt.quarantined.active", dltMessageStore, store -> store.getMessages().size())
                .description("Active number of un-replayed messages currently quarantined in DLT store")
                .register(meterRegistry);
    }
}
