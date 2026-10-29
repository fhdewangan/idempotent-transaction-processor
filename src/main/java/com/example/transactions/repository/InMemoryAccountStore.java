package com.example.transactions.repository;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

@Repository
public class InMemoryAccountStore {

    private final ConcurrentHashMap<String, BigDecimal> balances =
            new ConcurrentHashMap<>();

    public synchronized BigDecimal getBalance(String accountId) {
        return balances.getOrDefault(accountId, BigDecimal.ZERO);
    }

    public synchronized BigDecimal updateBalance(
            String accountId, BigDecimal amount) {

        BigDecimal current = getBalance(accountId);
        BigDecimal updated = current.add(amount);

        if (updated.signum() < 0) {
            throw new IllegalArgumentException(
                    "Insufficient funds for account: " + accountId);
        }

        balances.put(accountId, updated);
        return updated;
    }
}
