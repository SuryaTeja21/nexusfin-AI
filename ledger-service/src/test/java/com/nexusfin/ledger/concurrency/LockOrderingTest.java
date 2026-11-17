package com.nexusfin.ledger.concurrency;

import com.nexusfin.ledger.AbstractPostgresIT;
import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"nexusfin.kafka.outbox.enabled=false"})
class LockOrderingTest extends AbstractPostgresIT {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @Transactional
    @DisplayName("1.2 Bug Proof: findAllByIdsWithPessimisticLock must order rows strictly by id ascending")
    void findAllByIdsWithPessimisticLock_mustOrderByAscendingId() {
        // Create 3 accounts with deliberately out-of-order UUIDs
        // Insert order: id3, id1, id2
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        for (UUID id : List.of(id3, id1, id2)) {
            Account account = Account.builder()
                    .id(id)
                    .accountNumber("ACC-ORDER-" + id.toString().substring(0, 8))
                    .accountName("Test Account " + id)
                    .accountType(AccountType.ASSET)
                    .currency("USD")
                    .balance(new BigDecimal("1000.0000"))
                    .status(AccountStatus.ACTIVE)
                    .build();
            accountRepository.save(account);
        }

        // Current query in AccountRepository is:
        // @Query("SELECT a FROM Account a WHERE a.id IN :ids")
        // It lacks "ORDER BY a.id ASC". In PostgreSQL, without ORDER BY, the returned order
        // is NOT guaranteed to match sorted order (id1, id2, id3).
        List<Account> lockedAccounts = accountRepository.findAllByIdsWithPessimisticLock(List.of(id2, id3, id1));

        List<UUID> returnedIds = lockedAccounts.stream().map(Account::getId).toList();
        List<UUID> expectedSortedIds = List.of(id1, id2, id3);

        assertThat(returnedIds)
                .withFailMessage("Expected findAllByIdsWithPessimisticLock to return accounts sorted by ID ascending %s, but got %s",
                        expectedSortedIds, returnedIds)
                .isEqualTo(expectedSortedIds);
    }
}
