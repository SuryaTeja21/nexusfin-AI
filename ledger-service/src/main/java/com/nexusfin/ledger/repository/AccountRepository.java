package com.nexusfin.ledger.repository;

import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.repository.projection.AccountIdProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByAccountNumberIn(Collection<String> accountNumbers);

    @Query("SELECT a.accountNumber AS accountNumber, a.id AS id, a.accountType AS accountType FROM Account a WHERE a.accountNumber IN :accountNumbers")
    List<AccountIdProjection> findAccountIdsByAccountNumberIn(@Param("accountNumbers") Collection<String> accountNumbers);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * Obtains an exclusive PESSIMISTIC_WRITE lock on an account row to ensure ACID guarantees.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithPessimisticLock(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithLock(@Param("accountNumber") String accountNumber);

    /**
     * Locks a batch of accounts simultaneously with PESSIMISTIC_WRITE in strict ascending ID order.
     * Note: Enforces a deterministic ascending lock hierarchy to prevent deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findAllByIdsWithPessimisticLock(@Param("ids") Collection<UUID> ids);
}
