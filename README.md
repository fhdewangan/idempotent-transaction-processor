# Idempotent Background Transaction Processing Service

Java 17 / Spring Boot service for the FDE L2 practical assessment. It processes transaction requests that may arrive out of order, duplicated, or after a transient failure, without applying balance or ledger side effects twice.

Design choices, trade-offs, AI usage, and production hardening notes are in [DESIGN.md](DESIGN.md).

## Run

```bash
./mvnw test
./mvnw spring-boot:run
```

On Windows:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Server listens on `http://localhost:8081`.

Optional: set `processor.ingest-sample-on-startup=true` in `src/main/resources/application.properties` to process `sample-batch.json` at boot.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/transactions` | Ingest one request (API adapter) |
| `POST` | `/api/batches` | Ingest a JSON array without stopping on invalid items |
| `POST` | `/api/batches/sample` | Run the L2 sample file |
| `GET` | `/api/transactions/{transactionId}` | Transaction state |
| `GET` | `/api/transactions/summary` | Counts by status, next sequence, missing sequences |
| `GET` | `/api/accounts/{accountId}/balance` | Current balance |
| `GET` | `/api/accounts/{accountId}/ledger` | Ledger entries |
| `GET` | `/api/health` | Liveness |

## Sample batch

`src/main/resources/sample-batch.json` is the L2 suggested data set.

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/api/batches/sample" -Method Post
Invoke-RestMethod -Uri "http://localhost:8081/api/transactions/summary" -Method Get
Invoke-RestMethod -Uri "http://localhost:8081/api/accounts/ACC-201/balance" -Method Get
```

Expected business outcome after the sample run:

| transactionId | Expected status | Notes |
| --- | --- | --- |
| TXN-2002 (first) | `PENDING_SEQUENCE` then `PROCESSED` | Debit waits for sequence 1 |
| TXN-2001 | `PROCESSED` | Credit 100, then drains the debit |
| TXN-2002 / REQ-2-DUP | `DUPLICATE` | No second debit |
| TXN-2003 | `PROCESSED` | Credit 25 |
| TXN-2004 | `FAILED` | Amount `-10` fails validation |
| TXN-2005 | `RETRY_PENDING` then `PROCESSED` | Simulated transient failure, one retry |

Final balances: `ACC-201 = 85`, `ACC-203 = 500`. Ledger has four entries (`TXN-2001`, `TXN-2002`, `TXN-2003`, `TXN-2005`).

Single credit via PowerShell:

```powershell
$body = @{
  transactionId = "TXN-1001"
  requestId = "REQ-1001"
  accountId = "ACC-100"
  transactionType = "CREDIT"
  amount = 100
  currency = "INR"
  sequenceNumber = 1
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri "http://localhost:8081/api/transactions" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

Note: the in-memory store is process-local. Restarting the app resets state. Sequence numbers are a single partition starting at 1 for that process.
