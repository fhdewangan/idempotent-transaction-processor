# Design note — FDE L2 idempotent transaction processor

## Architecture

The service is a small Spring Boot process with a replaceable ingest edge and a single synchronized processing core.

```
API | JSON file | in-memory list
            ↓
   BatchProcessingService
            ↓
    TransactionProcessor
       ↓        ↓         ↓
 AccountStore LedgerStore StateStore
```

- **Ingest** (`TransactionSource`, HTTP controllers) only fetches and forwards records. It does not apply money movement.
- **Processor** owns validation, idempotency, sequence ordering, retries, and ledger application.
- **Stores** are in-memory so the assessment stays self-contained. Interfaces are narrow enough to swap for a database later.

This matches how an FDE would demo a worker that later binds to SQS, Kafka, or a DB-backed job table: the source adapter changes, the processing contract does not.

## Idempotency

Two keys are stored:

1. `requestId` — client retry of the same HTTP/queue delivery.
2. `transactionId` — business transaction uniqueness.

A fingerprint of `accountId|type|amount|currency|sequenceNumber|transactionId` is stored with the request. Replay with the same key and same fingerprint returns the existing outcome (`DUPLICATE` once `PROCESSED`). The same key with a different fingerprint is an **idempotency conflict** (`FAILED`), not a second apply.

Side effects (balance + ledger) happen only in `applyLedger`, after the request is in `PROCESSING`, and only if the ledger does not already contain that `transactionId`.

## Duplicate detection

- Same `transactionId` after `PROCESSED` → `DUPLICATE`, even if `requestId` is new (`REQ-2-DUP` in the L2 sample).
- Same `requestId` after success → `DUPLICATE`.
- Duplicates that arrive later in a batch are handled because state is checked before any apply.

## Out-of-order strategy (Option A)

A single processing partition maintains `nextExpectedSequence`, starting at `1`.

| Incoming sequence | Action |
| --- | --- |
| `> next` | Buffer, status `PENDING_SEQUENCE` |
| `== next` | Apply now |
| `< next` | `DUPLICATE` if already applied, otherwise stale `FAILED` |

When the head succeeds or permanently fails, the processor advances and drains the buffer. Invalid records at the head consume the sequence so one bad row does not stall the batch. Invalid records that arrive early are skipped when their sequence becomes due.

**Trade-off:** a global sequence is simple and matches the L2 sample (sequences 1–5 across accounts). In production this is a hotspot. The same algorithm should be sharded by `accountId` or by queue partition so accounts do not block each other. Per-account sequences starting at 1 would leave `TXN-2005` (seq 5 on a new account) pending forever, which is the wrong customer outcome for this data set.

Missing sequences are reported on `GET /api/transactions/summary`.

## Retry and failure handling

`FailureSimulator` fails the first attempt of `processor.transient-failure-transaction-id` (default `TXN-2005`). The record becomes `RETRY_PENDING` with no ledger write. `BatchProcessingService` retries immediately; `RetryScheduler` retries the head of the sequence every `processor.retry-interval-ms`.

After `processor.max-retries` the record is `FAILED` and the sequence advances so the rest of the stream is not stuck.

Retries are safe because apply is gated on status + ledger existence, not on “message received”.

Business failures (validation, insufficient funds) are **not** retried. They are isolated: the batch continues.

## Status lifecycle

`RECEIVED` → `PROCESSING` → `PROCESSED`  
or `PENDING_SEQUENCE` → `PROCESSING` → `PROCESSED`  
or `PROCESSING` → `RETRY_PENDING` → `PROCESSING` → `PROCESSED` / `FAILED`  
or `FAILED` / `DUPLICATE`

`REJECTED` is reserved; validation currently uses `FAILED` so operators can count all terminal errors in one bucket.

## Observability

Structured log events (no amounts): `pending_sequence`, `processing`, `processed`, `duplicate`, `retry_pending`, `failed`, `invalid_record`, `batch_complete`.

`GET /api/transactions/summary` returns counts by status, `nextExpectedSequence`, missing sequences, and ledger size.

## Security and data handling

- Bean Validation on the single-record API; programmatic validation in batch so one bad record does not fail the HTTP call.
- Logs include ids and status, not amounts or names.
- No secrets. Retry and failure targets are configuration, not code constants only.

## AI-assisted development

AI was used to scaffold Spring wiring, tests, and this note, then the processing rules were checked against the L2 sample by hand:

- Debit-before-credit must wait, not overdraft-fail.
- `REQ-2-DUP` must not write a second ledger line.
- Negative amount must fail validation without blocking later sequences.
- `TXN-2005` must retry once with a single credit.

Generated exception-mapping (`Insufficient balance` vs `Insufficient funds`) was rejected in favor of one message that matches the 422 contract.

## Production hardening (next steps)

1. Persist state, ledger, and accounts in one database transaction (or transactional outbox).
2. Shard sequence state by account or partition; use `SELECT … FOR UPDATE` (or an idempotency row unique on `requestId`).
3. Replace `FailureSimulator` with real timeouts/429s from downstream, plus exponential backoff and a DLQ.
4. Exactly-once *effect* still needs at-least-once *delivery*; keep the idempotency store as the source of truth.
5. Metrics (Micrometer): processed, duplicate, failed, retry, pending depth, apply latency.
6. Reversal as a compensating credit linked to `originalTransactionId`.
7. AuthN/Z on ingest APIs; encrypt data at rest when a real store is added.

## Assumptions

- One in-memory process is the unit of consistency; crash loses state (acceptable for the assessment, called out above).
- Sequence numbers are a single partition for the process.
- `REVERSAL` is rejected until a compensating model is designed.
- Opening balance is zero; credits must precede debits on an account unless a debit is buffered behind an earlier credit in sequence.
