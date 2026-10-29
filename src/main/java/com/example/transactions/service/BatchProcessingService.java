package com.example.transactions.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.transactions.ingest.TransactionSource;
import com.example.transactions.model.ProcessingResult;
import com.example.transactions.model.ProcessingSummary;
import com.example.transactions.model.TransactionRequest;
import com.example.transactions.model.TransactionStatus;

@Service
public class BatchProcessingService {

    private static final Logger log =
            LoggerFactory.getLogger(BatchProcessingService.class);

    private final TransactionProcessor processor;

    public BatchProcessingService(TransactionProcessor processor) {
        this.processor = processor;
    }

    public BatchRunResult process(TransactionSource source) {
        log.info("event=batch_start source={}", source.name());
        return process(source.name(), source.fetch());
    }

    public BatchRunResult process(
            String sourceName, List<TransactionRequest> requests) {

        List<ProcessingResult> results = new ArrayList<>();
        for (TransactionRequest request : requests) {
            try {
                results.add(processor.ingest(request));
            } catch (RuntimeException ex) {
                log.error(
                        "event=batch_item_isolated transactionId={}",
                        request == null ? "UNKNOWN" : request.transactionId(),
                        ex);
                results.add(ProcessingResult.of(
                        request == null ? "UNKNOWN" : request.transactionId(),
                        request == null ? "UNKNOWN" : request.requestId(),
                        TransactionStatus.FAILED,
                        ex.getMessage(),
                        0));
            }
        }

        ProcessingResult retried;
        while ((retried = processor.retryReady()) != null) {
            results.add(retried);
        }

        ProcessingSummary summary = processor.summary();
        log.info(
                "event=batch_complete source={} nextExpectedSequence={} ledgerEntries={}",
                sourceName,
                summary.nextExpectedSequence(),
                summary.ledgerEntries());
        return new BatchRunResult(sourceName, results, summary);
    }

    public record BatchRunResult(
            String source,
            List<ProcessingResult> results,
            ProcessingSummary summary
    ) {}
}
