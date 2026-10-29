package com.example.transactions.repository;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionStatus;

@Repository
public class InMemoryTransactionStateStore {

    private final ConcurrentHashMap<String, TransactionRecord> byTransactionId =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestToTransaction =
            new ConcurrentHashMap<>();

    public void save(TransactionRecord record) {
        byTransactionId.put(record.transactionId(), record);
        requestToTransaction.put(record.requestId(), record.transactionId());
    }

    public TransactionRecord findByTransactionId(String transactionId) {
        return byTransactionId.get(transactionId);
    }

    public TransactionRecord findByRequestId(String requestId) {
        String transactionId = requestToTransaction.get(requestId);
        if (transactionId == null) {
            return null;
        }
        return byTransactionId.get(transactionId);
    }

    public Collection<TransactionRecord> findAll() {
        return List.copyOf(byTransactionId.values());
    }

    public List<TransactionRecord> findByStatus(TransactionStatus status) {
        return byTransactionId.values().stream()
                .filter(record -> record.status() == status)
                .toList();
    }

    public Map<TransactionStatus, Long> countsByStatus() {
        Map<TransactionStatus, Long> counts =
                new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        for (TransactionRecord record : byTransactionId.values()) {
            counts.merge(record.status(), 1L, Long::sum);
        }
        return counts;
    }
}
