package com.example.transactions.service;

import org.springframework.stereotype.Component;

import com.example.transactions.config.ProcessorProperties;
import com.example.transactions.model.TransactionRequest;

@Component
public class FailureSimulator {

    private final ProcessorProperties properties;

    public FailureSimulator(ProcessorProperties properties) {
        this.properties = properties;
    }

    public boolean shouldFail(TransactionRequest request, int attempt) {
        String target = properties.transientFailureTransactionId();
        if (target == null || target.isBlank() || request == null) {
            return false;
        }
        return target.equals(request.transactionId()) && attempt == 1;
    }
}
