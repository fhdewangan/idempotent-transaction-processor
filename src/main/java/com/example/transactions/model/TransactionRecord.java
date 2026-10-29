package com.example.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRecord(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        Long sequenceNumber,
        String fingerprint,
        TransactionStatus status,
        int retryCount,
        String lastError,
        Instant updatedAt
) {
    public TransactionRecord withStatus(
            TransactionStatus newStatus, String error) {
        return new TransactionRecord(
                transactionId,
                requestId,
                accountId,
                transactionType,
                amount,
                currency,
                sequenceNumber,
                fingerprint,
                newStatus,
                retryCount,
                error,
                Instant.now());
    }

    public TransactionRecord withRetry(
            TransactionStatus newStatus, int retries, String error) {
        return new TransactionRecord(
                transactionId,
                requestId,
                accountId,
                transactionType,
                amount,
                currency,
                sequenceNumber,
                fingerprint,
                newStatus,
                retries,
                error,
                Instant.now());
    }
}
