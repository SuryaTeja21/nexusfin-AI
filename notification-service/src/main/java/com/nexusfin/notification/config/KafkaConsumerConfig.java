package com.nexusfin.notification.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            KafkaOperations<Object, Object> kafkaOperations,
            MeterRegistry meterRegistry) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, ex) -> {
                    String dltTopic = record.topic() + ".DLT";
                    log.warn("Notification service routing poison/failed message to DLT [{}], partition={}, offset={}, cause={}",
                            dltTopic, record.partition(), record.offset(), ex.getMessage());
                    meterRegistry.counter("dlt.messages",
                            "source.topic", record.topic(),
                            "service", "notification-service",
                            "exception", ex.getClass().getSimpleName()
                    ).increment();
                    return new TopicPartition(dltTopic, record.partition());
                }
        );

        // Exponential backoff: 2 retries (3 attempts total) with 100ms initial interval, multiplier 2.0
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(100L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(1000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Non-retryable exceptions
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                JsonParseException.class,
                JsonMappingException.class,
                IllegalArgumentException.class
        );

        return errorHandler;
    }
}
