package com.nexusfin.fraud.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ValidationException;
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
                    log.warn("Routing poison/failed message from [{}] to DLT [{}], partition={}, offset={}, cause={}",
                            record.topic(), dltTopic, record.partition(), record.offset(), ex.getMessage());
                    meterRegistry.counter("dlt.messages",
                            "source.topic", record.topic(),
                            "dlt.topic", dltTopic,
                            "exception", ex.getClass().getSimpleName()
                    ).increment();
                    return new TopicPartition(dltTopic, record.partition());
                }
        );

        // Exponential backoff: 2 retries (3 attempts total) with 100ms initial interval, multiplier 2.0, max 1000ms
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(100L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(1000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Classify non-retryable exceptions to fail fast and send directly to DLT
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                JsonParseException.class,
                JsonMappingException.class,
                ValidationException.class,
                IllegalArgumentException.class
        );

        return errorHandler;
    }
}
