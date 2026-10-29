package com.example.transactions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "processor")
public record ProcessorProperties(
        @DefaultValue("1") int maxRetries,
        @DefaultValue("TXN-2005") String transientFailureTransactionId,
        @DefaultValue("2000") long retryIntervalMs,
        @DefaultValue("false") boolean ingestSampleOnStartup
) {}
