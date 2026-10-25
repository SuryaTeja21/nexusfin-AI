package com.nexusfin.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * NexusFin AI - Core ACID Double-Entry Financial Ledger Microservice.
 * Powered by Spring Boot 3.2 on JDK 21 Virtual Threads.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
