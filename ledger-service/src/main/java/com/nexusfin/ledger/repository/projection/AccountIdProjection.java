package com.nexusfin.ledger.repository.projection;

import com.nexusfin.ledger.domain.model.AccountType;

import java.util.UUID;

public interface AccountIdProjection {
    String getAccountNumber();
    UUID getId();
    AccountType getAccountType();
}
