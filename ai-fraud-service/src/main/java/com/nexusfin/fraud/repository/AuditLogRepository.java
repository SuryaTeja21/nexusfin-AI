package com.nexusfin.fraud.repository;

import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {
    List<AuditLogEntry> findByTransactionReferenceOrderByCreatedAtDesc(String transactionReference);
    List<AuditLogEntry> findTop20ByOrderByCreatedAtDesc();
}
