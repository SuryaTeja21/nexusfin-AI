package com.nexusfin.ledger.repository;

import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Retrieves pending outbox events using pessimistic locking with SKIP LOCKED.
     * Prevents duplicate event processing across concurrent outbox publisher instances.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents(@Param("status") OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByAggregateId(String aggregateId);
}
