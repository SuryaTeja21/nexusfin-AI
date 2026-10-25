package com.nexusfin.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * NexusFin AI - AI-Powered Fraud Detection & Financial Audit Microservice.
 * Leverages Spring AI and JDK 21 Virtual Threads for concurrent inference.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class AiFraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiFraudServiceApplication.class, args);
    }
}
