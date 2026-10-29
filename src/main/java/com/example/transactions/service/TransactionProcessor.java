package com.example.transactions.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.transactions.config.ProcessorProperties;
import com.example.transactions.exception.TransientProcessingException;
import com.example.transactions.model.LedgerEntry;
import com.example.transactions.model.ProcessingResult;
import com.example.transactions.model.ProcessingSummary;
import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionRequest;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.model.TransactionType;
import com.example.transactions.repository.InMemoryAccountStore;
import com.example.transactions.repository.InMemoryLedgerStore;
import com.example.transactions.repository.InMemoryTransactionStateStore;

@Service
public class TransactionProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(TransactionProcessor.class);

    private final InMemoryAccountStore accounts;
    private final InMemoryLedgerStore ledger;
    private final InMemoryTransactionStateStore states;
    private final RequestValidator validator;
    private final FailureSimulator failureSimulator;
    private final ProcessorProperties properties;

    /**
     * Option A: one processing partition / global sequence.
     * Production would shard this by account or queue partition.
     */
    private long nextExpectedSequence = 1L;
    private final TreeMap<Long, TransactionRequest> sequenceBuffer =
            new TreeMap<>();
    private final Set<Long> skippedSequences = new HashSet<>();

    public TransactionProcessor(
            InMemoryAccountStore accounts,
            InMemoryLedgerStore ledger,
            InMemoryTransactionStateStore states,
            RequestValidator validator,
            FailureSimulator failureSimulator,
            ProcessorProperties properties) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.states = states;
        this.validator = validator;
        this.failureSimulator = failureSimulator;
        this.properties = properties;
    }

    public synchronized ProcessingResult ingest(TransactionRequest request) {
        String validationError = validator.validate(request);
        if (validationError != null) {
            return rejectInvalid(request, validationError);
        }

        String fingerprint = fingerprint(request);
        TransactionRecord byRequest =
                states.findByRequestId(request.requestId());
        if (byRequest != null) {
            return replayOrConflict(request, fingerprint, byRequest);
        }

        TransactionRecord byTxn =
                states.findByTransactionId(request.transactionId());
        if (byTxn != null) {
            if (!fingerprint.equals(byTxn.fingerprint())) {
                log.warn(
                        "event=idempotency_conflict transactionId={} requestId={}",
                        request.transactionId(),
                        request.requestId());
                return ProcessingResult.of(
                        request.transactionId(),
                        request.requestId(),
                        TransactionStatus.FAILED,
                        "Idempotency conflict: transactionId reused "
                                + "with different transaction data",
                        byTxn.retryCount());
            }
            if (byTxn.status() == TransactionStatus.PROCESSED) {
                return duplicate(
                        byTxn,
                        request.requestId(),
                        "Business transaction already processed");
            }
            return ProcessingResult.of(
                    byTxn.transactionId(),
                    byTxn.requestId(),
                    byTxn.status(),
                    "Existing transaction returned",
                    byTxn.retryCount());
        }

        save(request, fingerprint, TransactionStatus.RECEIVED, 0, null);
        return routeBySequence(request, fingerprint);
    }

    public synchronized ProcessingResult retryReady() {
        TransactionRecord head = states.findAll().stream()
                .filter(record ->
                        record.sequenceNumber() != null
                                && record.sequenceNumber()
                                        == nextExpectedSequence
                                && record.status()
                                        == TransactionStatus.RETRY_PENDING)
                .findFirst()
                .orElse(null);

        if (head == null) {
            return null;
        }

        return applyHead(toRequest(head), head.fingerprint(), head.retryCount());
    }

    public synchronized ProcessingSummary summary() {
        List<Long> missing = new ArrayList<>();
        Long maxKnown = sequenceBuffer.isEmpty()
                ? null
                : sequenceBuffer.lastKey();
        if (maxKnown != null) {
            for (long seq = nextExpectedSequence; seq <= maxKnown; seq++) {
                if (!sequenceBuffer.containsKey(seq)
                        && !skippedSequences.contains(seq)) {
                    missing.add(seq);
                }
            }
        }

        return new ProcessingSummary(
                states.countsByStatus(),
                nextExpectedSequence,
                missing,
                ledger.findAll().size());
    }

    public TransactionRecord getRecord(String transactionId) {
        return states.findByTransactionId(transactionId);
    }

    public long nextExpectedSequence() {
        return nextExpectedSequence;
    }

    private ProcessingResult routeBySequence(
            TransactionRequest request, String fingerprint) {

        long seq = request.sequenceNumber();

        if (seq > nextExpectedSequence) {
            sequenceBuffer.put(seq, request);
            save(request, fingerprint,
                    TransactionStatus.PENDING_SEQUENCE, 0,
                    "Waiting for sequence " + nextExpectedSequence);
            log.info(
                    "event=pending_sequence transactionId={} requestId={} seq={} next={}",
                    request.transactionId(),
                    request.requestId(),
                    seq,
                    nextExpectedSequence);
            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.PENDING_SEQUENCE,
                    "Waiting for sequence " + nextExpectedSequence,
                    0);
        }

        if (seq < nextExpectedSequence) {
            if (ledger.existsForTransaction(request.transactionId())) {
                return duplicate(
                        states.findByTransactionId(request.transactionId()),
                        request.requestId(),
                        "Stale sequence for an already processed transaction");
            }
            save(request, fingerprint, TransactionStatus.FAILED, 0,
                    "Stale sequence " + seq);
            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.FAILED,
                    "Stale sequence " + seq + "; next expected is "
                            + nextExpectedSequence,
                    0);
        }

        return applyHead(request, fingerprint, 0);
    }

    private ProcessingResult applyHead(
            TransactionRequest request,
            String fingerprint,
            int retryCount) {

        save(request, fingerprint, TransactionStatus.PROCESSING, retryCount, null);
        log.info(
                "event=processing transactionId={} requestId={} seq={} attempt={}",
                request.transactionId(),
                request.requestId(),
                request.sequenceNumber(),
                retryCount + 1);

        try {
            if (failureSimulator.shouldFail(request, retryCount + 1)) {
                throw new TransientProcessingException(
                        "Simulated transient failure for "
                                + request.transactionId());
            }

            applyLedger(request);
            save(request, fingerprint, TransactionStatus.PROCESSED, retryCount, null);
            log.info(
                    "event=processed transactionId={} requestId={} seq={}",
                    request.transactionId(),
                    request.requestId(),
                    request.sequenceNumber());

            advanceSequence();

            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.PROCESSED,
                    "Processed",
                    retryCount);

        } catch (TransientProcessingException ex) {
            int nextRetry = retryCount + 1;
            if (nextRetry <= properties.maxRetries()) {
                save(request, fingerprint,
                        TransactionStatus.RETRY_PENDING,
                        nextRetry,
                        ex.getMessage());
                log.warn(
                        "event=retry_pending transactionId={} requestId={} attempt={}",
                        request.transactionId(),
                        request.requestId(),
                        nextRetry);
                return ProcessingResult.of(
                        request.transactionId(),
                        request.requestId(),
                        TransactionStatus.RETRY_PENDING,
                        ex.getMessage(),
                        nextRetry);
            }

            save(request, fingerprint, TransactionStatus.FAILED, nextRetry,
                    ex.getMessage());
            log.error(
                    "event=failed_after_retries transactionId={} requestId={}",
                    request.transactionId(),
                    request.requestId());
            advanceSequence();
            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.FAILED,
                    ex.getMessage(),
                    nextRetry);

        } catch (RuntimeException ex) {
            save(request, fingerprint, TransactionStatus.FAILED, retryCount,
                    ex.getMessage());
            log.warn(
                    "event=failed transactionId={} requestId={} reason={}",
                    request.transactionId(),
                    request.requestId(),
                    ex.getMessage());
            advanceSequence();
            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.FAILED,
                    ex.getMessage(),
                    retryCount);
        }
    }

    private void advanceSequence() {
        nextExpectedSequence++;
        drainBuffer();
    }

    private void drainBuffer() {
        while (true) {
            if (skippedSequences.remove(nextExpectedSequence)) {
                nextExpectedSequence++;
                continue;
            }
            if (!sequenceBuffer.containsKey(nextExpectedSequence)) {
                return;
            }
            TransactionRequest next = sequenceBuffer.remove(nextExpectedSequence);
            ProcessingResult result = applyHead(next, fingerprint(next), 0);
            if (result.status() == TransactionStatus.RETRY_PENDING) {
                return;
            }
        }
    }

    private void applyLedger(TransactionRequest request) {
        var balanceChange = switch (request.transactionType()) {
            case CREDIT -> request.amount();
            case DEBIT -> request.amount().negate();
            case REVERSAL -> throw new IllegalArgumentException(
                    "REVERSAL is not supported in this assessment build");
        };

        if (request.transactionType() == TransactionType.DEBIT
                && accounts.getBalance(request.accountId())
                        .compareTo(request.amount()) < 0) {
            throw new IllegalArgumentException(
                    "Insufficient funds for account: " + request.accountId());
        }

        var newBalance = accounts.updateBalance(
                request.accountId(), balanceChange);

        ledger.add(new LedgerEntry(
                request.transactionId(),
                request.accountId(),
                request.transactionType(),
                request.amount(),
                request.currency(),
                newBalance,
                Instant.now()));
    }

    private ProcessingResult replayOrConflict(
            TransactionRequest request,
            String fingerprint,
            TransactionRecord existing) {

        if (!fingerprint.equals(existing.fingerprint())
                || !request.transactionId().equals(existing.transactionId())) {
            log.warn(
                    "event=idempotency_conflict requestId={} transactionId={}",
                    request.requestId(),
                    request.transactionId());
            return ProcessingResult.of(
                    request.transactionId(),
                    request.requestId(),
                    TransactionStatus.FAILED,
                    "Idempotency conflict: requestId reused "
                            + "with different transaction data",
                    existing.retryCount());
        }

        if (existing.status() == TransactionStatus.PROCESSED) {
            return duplicate(
                    existing, request.requestId(), "Request already processed");
        }

        if (existing.status() == TransactionStatus.RETRY_PENDING
                && existing.sequenceNumber() == nextExpectedSequence) {
            return applyHead(request, fingerprint, existing.retryCount());
        }

        return ProcessingResult.of(
                existing.transactionId(),
                existing.requestId(),
                existing.status(),
                "Existing request returned",
                existing.retryCount());
    }

    private ProcessingResult rejectInvalid(
            TransactionRequest request, String validationError) {

        String transactionId = request == null
                ? "UNKNOWN"
                : request.transactionId();
        String requestId = request == null ? "UNKNOWN" : request.requestId();

        if (request != null && request.transactionId() != null
                && !request.transactionId().isBlank()) {
            String fingerprint = "invalid";
            if (request.amount() != null && request.sequenceNumber() != null
                    && request.transactionType() != null
                    && request.currency() != null) {
                fingerprint = fingerprint(request);
            }
            states.save(new TransactionRecord(
                    request.transactionId(),
                    request.requestId() == null ? "UNKNOWN" : request.requestId(),
                    request.accountId(),
                    request.transactionType(),
                    request.amount(),
                    request.currency(),
                    request.sequenceNumber(),
                    fingerprint,
                    TransactionStatus.FAILED,
                    0,
                    validationError,
                    Instant.now()));
        }

        if (request != null && request.sequenceNumber() != null) {
            long seq = request.sequenceNumber();
            if (seq == nextExpectedSequence) {
                advanceSequence();
            } else if (seq > nextExpectedSequence) {
                skippedSequences.add(seq);
            }
        }

        log.warn(
                "event=invalid_record transactionId={} requestId={} reason={}",
                transactionId,
                requestId,
                validationError);

        return ProcessingResult.of(
                transactionId,
                requestId,
                TransactionStatus.FAILED,
                validationError,
                0);
    }

    private ProcessingResult duplicate(
            TransactionRecord existing, String requestId, String message) {
        log.info(
                "event=duplicate transactionId={} requestId={}",
                existing.transactionId(),
                requestId);
        return ProcessingResult.of(
                existing.transactionId(),
                requestId,
                TransactionStatus.DUPLICATE,
                message,
                existing.retryCount());
    }

    private void save(
            TransactionRequest request,
            String fingerprint,
            TransactionStatus status,
            int retryCount,
            String error) {
        states.save(new TransactionRecord(
                request.transactionId(),
                request.requestId(),
                request.accountId(),
                request.transactionType(),
                request.amount(),
                request.currency(),
                request.sequenceNumber(),
                fingerprint,
                status,
                retryCount,
                error,
                Instant.now()));
    }

    private TransactionRequest toRequest(TransactionRecord record) {
        return new TransactionRequest(
                record.transactionId(),
                record.requestId(),
                record.accountId(),
                record.transactionType(),
                record.amount(),
                record.currency(),
                record.sequenceNumber(),
                record.updatedAt());
    }

    static String fingerprint(TransactionRequest request) {
        return request.accountId() + "|"
                + request.transactionType() + "|"
                + request.amount().stripTrailingZeros().toPlainString()
                + "|" + request.currency() + "|"
                + request.sequenceNumber() + "|"
                + request.transactionId();
    }
}
