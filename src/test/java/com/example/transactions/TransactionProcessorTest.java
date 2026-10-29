package com.example.transactions;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.transactions.config.ProcessorProperties;
import com.example.transactions.ingest.TransactionSource;
import com.example.transactions.model.ProcessingResult;
import com.example.transactions.model.TransactionRequest;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.model.TransactionType;
import com.example.transactions.repository.InMemoryAccountStore;
import com.example.transactions.repository.InMemoryLedgerStore;
import com.example.transactions.repository.InMemoryTransactionStateStore;
import com.example.transactions.service.BatchProcessingService;
import com.example.transactions.service.FailureSimulator;
import com.example.transactions.service.RequestValidator;
import com.example.transactions.service.TransactionProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionProcessorTest {

    private InMemoryAccountStore accounts;
    private InMemoryLedgerStore ledger;
    private InMemoryTransactionStateStore states;
    private TransactionProcessor processor;
    private BatchProcessingService batches;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountStore();
        ledger = new InMemoryLedgerStore();
        states = new InMemoryTransactionStateStore();
        ProcessorProperties properties = new ProcessorProperties(
                1, "TXN-2005", 2000L, false);
        processor = new TransactionProcessor(
                accounts,
                ledger,
                states,
                new RequestValidator(),
                new FailureSimulator(properties),
                properties);
        batches = new BatchProcessingService(processor);
    }

    private TransactionRequest request(
            String transactionId,
            String requestId,
            String accountId,
            TransactionType type,
            BigDecimal amount,
            long sequenceNumber) {

        return new TransactionRequest(
                transactionId,
                requestId,
                accountId,
                type,
                amount,
                "INR",
                sequenceNumber,
                null
        );
    }

    @Test
    void creditShouldIncreaseBalance() {
        ProcessingResult result = processor.ingest(request(
                "CREDIT-1", "REQ-CREDIT-1", "ACC-1",
                TransactionType.CREDIT, new BigDecimal("500"), 1));

        assertEquals(TransactionStatus.PROCESSED, result.status());
        assertEquals(0, new BigDecimal("500").compareTo(
                accounts.getBalance("ACC-1")));
    }

    @Test
    void debitShouldReduceBalance() {
        processor.ingest(request(
                "CREDIT-2", "REQ-CREDIT-2", "ACC-2",
                TransactionType.CREDIT, new BigDecimal("500"), 1));

        ProcessingResult debit = processor.ingest(request(
                "DEBIT-1", "REQ-DEBIT-1", "ACC-2",
                TransactionType.DEBIT, new BigDecimal("200"), 2));

        assertEquals(TransactionStatus.PROCESSED, debit.status());
        assertEquals(0, new BigDecimal("300").compareTo(
                accounts.getBalance("ACC-2")));
    }

    @Test
    void insufficientFundsShouldNotChangeBalanceOrLedger() {
        processor.ingest(request(
                "CREDIT-3", "REQ-CREDIT-3", "ACC-3",
                TransactionType.CREDIT, new BigDecimal("500"), 1));

        ProcessingResult debit = processor.ingest(request(
                "DEBIT-2", "REQ-DEBIT-2", "ACC-3",
                TransactionType.DEBIT, new BigDecimal("600"), 2));

        assertEquals(TransactionStatus.FAILED, debit.status());
        assertTrue(debit.message().startsWith("Insufficient funds"));
        assertEquals(0, new BigDecimal("500").compareTo(
                accounts.getBalance("ACC-3")));
        assertEquals(1, ledger.findByAccountId("ACC-3").size());
    }

    @Test
    void duplicateDebitShouldNotReduceBalanceTwice() {
        processor.ingest(request(
                "CREDIT-4", "REQ-CREDIT-4", "ACC-4",
                TransactionType.CREDIT, new BigDecimal("500"), 1));

        TransactionRequest debit = request(
                "DEBIT-3", "REQ-DEBIT-3", "ACC-4",
                TransactionType.DEBIT, new BigDecimal("200"), 2);

        assertEquals(TransactionStatus.PROCESSED, processor.ingest(debit).status());
        assertEquals(TransactionStatus.DUPLICATE, processor.ingest(debit).status());
        assertEquals(0, new BigDecimal("300").compareTo(
                accounts.getBalance("ACC-4")));
        assertEquals(2, ledger.findByAccountId("ACC-4").size());
    }

    @Test
    void reusingRequestIdWithDifferentDataShouldFail() {
        processor.ingest(request(
                "TEST-TXN-3", "TEST-REQ-3", "TEST-ACC-3",
                TransactionType.CREDIT, new BigDecimal("100"), 1));

        ProcessingResult changed = processor.ingest(request(
                "TEST-TXN-4", "TEST-REQ-3", "TEST-ACC-3",
                TransactionType.CREDIT, new BigDecimal("200"), 1));

        assertEquals(TransactionStatus.FAILED, changed.status());
        assertTrue(changed.message().startsWith("Idempotency conflict"));
        assertEquals(0, new BigDecimal("100").compareTo(
                accounts.getBalance("TEST-ACC-3")));
    }

    @Test
    void outOfOrderDebitIsBufferedUntilPriorSequenceArrives() {
        ProcessingResult pending = processor.ingest(request(
                "TXN-2002", "REQ-2", "ACC-201",
                TransactionType.DEBIT, new BigDecimal("40"), 2));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, pending.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(accounts.getBalance("ACC-201")));

        ProcessingResult credit = processor.ingest(request(
                "TXN-2001", "REQ-1", "ACC-201",
                TransactionType.CREDIT, new BigDecimal("100"), 1));
        assertEquals(TransactionStatus.PROCESSED, credit.status());
        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-2002").status());
        assertEquals(0, new BigDecimal("60").compareTo(
                accounts.getBalance("ACC-201")));
    }

    @Test
    void invalidRecordIsIsolatedAndDoesNotStopTheBatch() {
        List<TransactionRequest> batch = List.of(
                request("TXN-A", "REQ-A", "ACC-A",
                        TransactionType.CREDIT, new BigDecimal("10"), 1),
                request("TXN-B", "REQ-B", "ACC-B",
                        TransactionType.DEBIT, new BigDecimal("-10"), 2),
                request("TXN-C", "REQ-C", "ACC-C",
                        TransactionType.CREDIT, new BigDecimal("5"), 3)
        );

        BatchProcessingService.BatchRunResult result =
                batches.process("test", batch);

        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-A").status());
        assertEquals(TransactionStatus.FAILED,
                processor.getRecord("TXN-B").status());
        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-C").status());
        assertEquals(2, result.summary().ledgerEntries());
    }

    @Test
    void transientFailureIsRetriedWithoutDuplicateLedgerEffect() {
        ProcessingResult first = processor.ingest(request(
                "TXN-2005", "REQ-5", "ACC-203",
                TransactionType.CREDIT, new BigDecimal("500"), 1));
        assertEquals(TransactionStatus.RETRY_PENDING, first.status());
        assertEquals(0, ledger.findByAccountId("ACC-203").size());

        ProcessingResult second = processor.retryReady();
        assertEquals(TransactionStatus.PROCESSED, second.status());
        assertEquals(1, ledger.findByAccountId("ACC-203").size());
        assertEquals(0, new BigDecimal("500").compareTo(
                accounts.getBalance("ACC-203")));
    }

    @Test
    void l2SampleBatchMatchesExpectedOutcomes() {
        List<TransactionRequest> sample = List.of(
                request("TXN-2002", "REQ-2", "ACC-201",
                        TransactionType.DEBIT, new BigDecimal("40"), 2),
                request("TXN-2001", "REQ-1", "ACC-201",
                        TransactionType.CREDIT, new BigDecimal("100"), 1),
                request("TXN-2002", "REQ-2-DUP", "ACC-201",
                        TransactionType.DEBIT, new BigDecimal("40"), 2),
                request("TXN-2003", "REQ-3", "ACC-201",
                        TransactionType.CREDIT, new BigDecimal("25"), 3),
                request("TXN-2004", "REQ-4", "ACC-202",
                        TransactionType.DEBIT, new BigDecimal("-10"), 4),
                request("TXN-2005", "REQ-5", "ACC-203",
                        TransactionType.CREDIT, new BigDecimal("500"), 5)
        );

        TransactionSource source = new TransactionSource() {
            @Override
            public String name() {
                return "l2-sample";
            }

            @Override
            public List<TransactionRequest> fetch() {
                return sample;
            }
        };

        BatchProcessingService.BatchRunResult run = batches.process(source);

        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-2001").status());
        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-2002").status());
        assertEquals(TransactionStatus.DUPLICATE, run.results().stream()
                .filter(result -> "REQ-2-DUP".equals(result.requestId())
                        || (result.status() == TransactionStatus.DUPLICATE
                        && "TXN-2002".equals(result.transactionId())))
                .findFirst()
                .orElseThrow()
                .status());
        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-2003").status());
        assertEquals(TransactionStatus.FAILED,
                processor.getRecord("TXN-2004").status());
        assertEquals(TransactionStatus.PROCESSED,
                processor.getRecord("TXN-2005").status());

        assertEquals(0, new BigDecimal("85").compareTo(
                accounts.getBalance("ACC-201")));
        assertEquals(0, new BigDecimal("500").compareTo(
                accounts.getBalance("ACC-203")));
        assertEquals(1, ledger.findByAccountId("ACC-201").stream()
                .filter(entry -> "TXN-2002".equals(entry.transactionId()))
                .count());
        assertEquals(4, run.summary().ledgerEntries());
    }

@Test
void exhaustedRetriesShouldFailWithoutLedgerEffectAndAdvanceSequence() {
    ProcessorProperties retryProperties = new ProcessorProperties(
            1, "TXN-FAIL", 2000L, false);

    InMemoryAccountStore testAccounts = new InMemoryAccountStore();
    InMemoryLedgerStore testLedger = new InMemoryLedgerStore();
    InMemoryTransactionStateStore testStates =
            new InMemoryTransactionStateStore();

    FailureSimulator alwaysFail = new FailureSimulator(retryProperties) {
        @Override
        public boolean shouldFail(TransactionRequest request, int attempt) {
            return "TXN-FAIL".equals(request.transactionId());
        }
    };

    TransactionProcessor testProcessor = new TransactionProcessor(
            testAccounts,
            testLedger,
            testStates,
            new RequestValidator(),
            alwaysFail,
            retryProperties);

    ProcessingResult first = testProcessor.ingest(request(
            "TXN-FAIL", "REQ-FAIL", "ACC-FAIL",
            TransactionType.CREDIT, new BigDecimal("100"), 1));

    assertEquals(TransactionStatus.RETRY_PENDING, first.status());

    ProcessingResult second = testProcessor.retryReady();

    assertEquals(TransactionStatus.FAILED, second.status());
    assertEquals(TransactionStatus.FAILED,
            testProcessor.getRecord("TXN-FAIL").status());
    assertEquals(0, testLedger.findByAccountId("ACC-FAIL").size());
    assertEquals(0, BigDecimal.ZERO.compareTo(
            testAccounts.getBalance("ACC-FAIL")));
    assertEquals(2, testProcessor.nextExpectedSequence());

    ProcessingResult next = testProcessor.ingest(request(
            "TXN-NEXT", "REQ-NEXT", "ACC-NEXT",
            TransactionType.CREDIT, new BigDecimal("50"), 2));

    assertEquals(TransactionStatus.PROCESSED, next.status());
    assertEquals(1, testLedger.findByAccountId("ACC-NEXT").size());
}

}
