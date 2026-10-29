package com.nexusfin.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * RFC 7807 Problem Details Fallback Controller.
 * Invoked by Resilience4j Circuit Breaker when downstream microservices are degraded or unavailable.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/ledger")
    public ResponseEntity<Map<String, Object>> ledgerFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "type", "https://nexusfin.ai/errors/ledger-service-unavailable",
                "title", "Ledger Service Temporarily Unavailable",
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "detail", "The core ACID ledger engine is currently degraded or experiencing high latency. Circuit breaker activated to prevent cascading failure.",
                "timestamp", Instant.now().toString()
        ));
    }

    @RequestMapping("/fraud")
    public ResponseEntity<Map<String, Object>> fraudFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "type", "https://nexusfin.ai/errors/fraud-service-unavailable",
                "title", "AI Fraud Service Temporarily Unavailable",
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "detail", "The AI Fraud Detection service is unreachable. Circuit breaker activated.",
                "timestamp", Instant.now().toString()
        ));
    }
}
