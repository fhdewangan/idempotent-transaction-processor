package com.example.transactions.model;

public record ProcessingResult(
        String transactionId,
        String requestId,
        TransactionStatus status,
        String message,
        int retryCount
) {
    public static ProcessingResult of(
            String transactionId,
            String requestId,
            TransactionStatus status,
            String message,
            int retryCount) {
        return new ProcessingResult(
                transactionId, requestId, status, message, retryCount);
    }
}
