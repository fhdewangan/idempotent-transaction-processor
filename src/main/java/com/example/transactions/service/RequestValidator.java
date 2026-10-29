package com.example.transactions.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.example.transactions.model.TransactionRequest;
import com.example.transactions.model.TransactionType;

@Component
public class RequestValidator {

    public String validate(TransactionRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (isBlank(request.transactionId())) {
            return "transactionId is required";
        }
        if (isBlank(request.requestId())) {
            return "requestId is required";
        }
        if (isBlank(request.accountId())) {
            return "accountId is required";
        }
        if (request.transactionType() == null) {
            return "transactionType is required";
        }
        if (request.amount() == null) {
            return "amount is required";
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return "amount must be positive";
        }
        if (isBlank(request.currency())) {
            return "currency is required";
        }
        if (request.sequenceNumber() == null
                || request.sequenceNumber() <= 0) {
            return "sequenceNumber must be a positive integer";
        }
        if (request.transactionType() == TransactionType.REVERSAL) {
            return "REVERSAL is not supported in this assessment build";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
