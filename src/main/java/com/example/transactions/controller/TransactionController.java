package com.example.transactions.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.transactions.ingest.ClasspathFileTransactionSource;
import com.example.transactions.model.LedgerEntry;
import com.example.transactions.model.ProcessingResult;
import com.example.transactions.model.ProcessingSummary;
import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionRequest;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.repository.InMemoryAccountStore;
import com.example.transactions.repository.InMemoryLedgerStore;
import com.example.transactions.service.BatchProcessingService;
import com.example.transactions.service.TransactionProcessor;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionProcessor processor;
    private final BatchProcessingService batches;
    private final ClasspathFileTransactionSource sampleSource;
    private final InMemoryAccountStore accounts;
    private final InMemoryLedgerStore ledger;

    public TransactionController(
            TransactionProcessor processor,
            BatchProcessingService batches,
            ClasspathFileTransactionSource sampleSource,
            InMemoryAccountStore accounts,
            InMemoryLedgerStore ledger) {
        this.processor = processor;
        this.batches = batches;
        this.sampleSource = sampleSource;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @PostMapping("/transactions")
    public ResponseEntity<ProcessingResult> submit(
            @Valid @RequestBody TransactionRequest request) {
        ProcessingResult result = processor.ingest(request);
        return ResponseEntity.status(httpStatus(result)).body(result);
    }

    @PostMapping("/batches")
    public BatchProcessingService.BatchRunResult submitBatch(
            @RequestBody List<TransactionRequest> requests) {
        return batches.process("api", requests);
    }

    @PostMapping("/batches/sample")
    public BatchProcessingService.BatchRunResult submitSampleBatch() {
        return batches.process(sampleSource);
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionRecord getTransaction(
            @PathVariable String transactionId) {

        TransactionRecord record = processor.getRecord(transactionId);
        if (record == null) {
            throw new TransactionNotFoundException(transactionId);
        }
        return record;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String, Object> getBalance(
            @PathVariable String accountId) {

        BigDecimal balance = accounts.getBalance(accountId);
        return Map.of(
                "accountId", accountId,
                "balance", balance
        );
    }

    @GetMapping("/accounts/{accountId}/ledger")
    public List<LedgerEntry> getLedger(
            @PathVariable String accountId) {
        return ledger.findByAccountId(accountId);
    }

    @GetMapping("/transactions/summary")
    public ProcessingSummary getSummary() {
        return processor.summary();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private HttpStatus httpStatus(ProcessingResult result) {
        if (result.status() == TransactionStatus.FAILED) {
            String message = result.message() == null ? "" : result.message();
            if (message.startsWith("Insufficient funds")) {
                return HttpStatus.UNPROCESSABLE_ENTITY;
            }
            if (message.startsWith("Idempotency conflict")) {
                return HttpStatus.CONFLICT;
            }
            return HttpStatus.BAD_REQUEST;
        }
        if (result.status() == TransactionStatus.PENDING_SEQUENCE
                || result.status() == TransactionStatus.RETRY_PENDING) {
            return HttpStatus.ACCEPTED;
        }
        return HttpStatus.OK;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(String transactionId) {
            super("Transaction not found: " + transactionId);
        }
    }
}
