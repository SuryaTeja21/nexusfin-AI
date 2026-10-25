package com.nexusfin.fraud.controller;

import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.repository.AuditLogRepository;
import com.nexusfin.fraud.service.AiFraudDetectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fraud")
@RequiredArgsConstructor
public class FraudDetectionController {

    private final AiFraudDetectionService fraudDetectionService;
    private final AuditLogRepository auditLogRepository;

    @PostMapping("/evaluate")
    public ResponseEntity<FraudEvaluationResponse> evaluateTransaction(@Valid @RequestBody FraudEvaluationRequest request) {
        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLogEntry>> getRecentAuditLogs() {
        return ResponseEntity.ok(auditLogRepository.findTop20ByOrderByCreatedAtDesc());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getEngineStatus() {
        return ResponseEntity.ok(Map.of(
                "service", "NexusFin AI Fraud Detection Intelligence",
                "status", "ACTIVE",
                "engine", "Dual Spring AI LLM & Heuristic Anomaly Pipeline",
                "virtualThreadsEnabled", true
        ));
    }
}
