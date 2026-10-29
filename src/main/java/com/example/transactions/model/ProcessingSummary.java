package com.example.transactions.model;

import java.util.List;
import java.util.Map;

public record ProcessingSummary(
        Map<TransactionStatus, Long> countsByStatus,
        long nextExpectedSequence,
        List<Long> missingSequences,
        long ledgerEntries
) {}
