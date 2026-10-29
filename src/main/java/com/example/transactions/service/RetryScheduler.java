package com.example.transactions.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(RetryScheduler.class);

    private final TransactionProcessor processor;

    public RetryScheduler(TransactionProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${processor.retry-interval-ms:2000}")
    public void retryTransientFailures() {
        var result = processor.retryReady();
        if (result != null) {
            log.info(
                    "event=scheduled_retry transactionId={} status={}",
                    result.transactionId(),
                    result.status());
        }
    }
}
