package com.example.transactions.repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Repository;

import com.example.transactions.model.LedgerEntry;

@Repository
public class InMemoryLedgerStore {

    private final CopyOnWriteArrayList<LedgerEntry> entries =
            new CopyOnWriteArrayList<>();

    public void add(LedgerEntry entry) {
        entries.add(entry);
    }

    public List<LedgerEntry> findByAccountId(String accountId) {
        return entries.stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .toList();
    }

    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }

    public boolean existsForTransaction(String transactionId) {
        return entries.stream()
                .anyMatch(entry ->
                        entry.transactionId().equals(transactionId));
    }
}
