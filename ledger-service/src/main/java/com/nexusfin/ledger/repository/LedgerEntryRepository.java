package com.nexusfin.ledger.repository;

import com.nexusfin.ledger.domain.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("SELECT e FROM LedgerEntry e WHERE e.account.id = :accountId ORDER BY e.createdAt DESC")
    Page<LedgerEntry> findByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    @Query("SELECT e FROM LedgerEntry e WHERE e.account.id = :accountId ORDER BY e.createdAt DESC")
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(@Param("accountId") UUID accountId);

    @Query("SELECT e FROM LedgerEntry e WHERE e.transaction.id = :transactionId ORDER BY e.sequenceNumber ASC")
    List<LedgerEntry> findByTransactionIdOrderBySequenceNumberAsc(@Param("transactionId") UUID transactionId);
}
