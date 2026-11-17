package com.nexusfin.ledger.repository;

import com.nexusfin.ledger.domain.entity.Transaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @EntityGraph(attributePaths = {"entries", "entries.account"})
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"entries", "entries.account"})
    Optional<Transaction> findByTransactionReference(String transactionReference);

    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.entries e LEFT JOIN FETCH e.account WHERE t.id = :id")
    Optional<Transaction> findByIdWithEntries(@Param("id") UUID id);

    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.entries e LEFT JOIN FETCH e.account WHERE t.transactionReference = :reference")
    Optional<Transaction> findByReferenceWithEntries(@Param("reference") String reference);
}
