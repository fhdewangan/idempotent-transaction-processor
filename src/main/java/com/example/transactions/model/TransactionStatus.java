
package com.example.transactions.model;

public enum TransactionStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    FAILED,
    RETRY_PENDING,
    PENDING_SEQUENCE,
    REJECTED
}
