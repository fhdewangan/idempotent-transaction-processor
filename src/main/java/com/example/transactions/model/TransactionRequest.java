
package com.example.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransactionRequest(
        @NotBlank String transactionId,
        @NotBlank String requestId,
        @NotBlank String accountId,
        @NotNull TransactionType transactionType,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull @Positive Long sequenceNumber,
        Instant eventTime
) {}
