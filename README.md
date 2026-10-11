# TxnRecon — Digital Transaction Reconciliation Engine

> **Educational Disclaimer**: TxnRecon is a **simplified educational model** built to demonstrate transaction reconciliation, turnaround time (TAT) tracking, and exception handling in Java. It is **not** a certified implementation of Reserve Bank of India (RBI) Harmonisation of Turnaround Time regulations and does not claim full regulatory compliance.

---

## 1. Project Purpose & Scope

In digital payments (UPI, IMPS, ATM, Card POS), a customer's bank account may be debited (`ledger.csv`) even when the downstream payment switch (`switch.csv`) records the transaction as failed (`FAILED`), or communication drops altogether. When a debit fails, the bank must reverse the funds (`reversals.csv`) within a channel-specific Turnaround Time (TAT). If the reversal happens after the due date—or remains unreversed past the due date—the customer is entitled to daily compensation.

**TxnRecon** ingests three synthetic CSV files, validates every row, normalizes transaction references, reconciles each unique reference into one of **10 deterministic outcomes**, calculates overdue calendar days and illustrative compensation using `BigDecimal`, and prints an executive console report.

---

## 2. Architecture & Class Responsibilities

The project follows a standard Maven layout under `com.txnrecon`:

```text
txnrecon/
├── pom.xml
├── data/
│   ├── ledger.csv
│   ├── switch.csv
│   └── reversals.csv
└── src/
    ├── main/java/com/txnrecon/
    │   ├── Main.java                              # CLI entry point (optional dataDir & asOfDate args)
    │   ├── model/
    │   │   ├── TransactionType.java               # Enum of payment channels & default TAT days
    │   │   ├── SwitchStatus.java                  # Enum: SUCCESS, FAILED
    │   │   ├── ReconOutcome.java                  # Enum of the 10 reconciliation outcomes
    │   │   ├── LedgerEntry.java                   # Immutable record for ledger.csv rows
    │   │   ├── SwitchRecord.java                  # Immutable record for switch.csv rows
    │   │   ├── ReversalRecord.java                # Immutable record for reversals.csv rows
    │   │   └── ReconciliationResult.java          # Immutable record for per-reference outcome
    │   ├── engine/
    │   │   ├── ReferenceNormalizer.java           # Uppercase + alphanumeric RRN normalization
    │   │   ├── CompensationCalculator.java        # Due date, days late, and BigDecimal compensation
    │   │   └── ReconciliationEngine.java          # Core 3-way reconciliation rules & deduplication
    │   ├── io/
    │   │   ├── CsvLoader.java                     # CSV reader & row-level field validator
    │   │   ├── CsvLoadResult.java                 # Holds valid records + rejected row errors
    │   │   └── CsvValidationError.java            # Rejected row metadata (file, line #, reason)
    │   ├── exception/
    │   │   └── CsvValidationException.java        # Domain validation exception with line numbers
    │   └── report/
    │       └── ReportGenerator.java               # Formats breaches, full table, counts & errors
    └── test/java/com/txnrecon/
        ├── engine/
        │   ├── ReferenceNormalizerAndCalculatorTest.java
        │   └── ReconciliationEngineTest.java
        └── io/
            └── CsvLoaderAndReportTest.java
```

---

## 3. Input CSV Formats

All dates must use ISO-8601 format (`yyyy-MM-dd`). Monetary amounts must be strictly positive numbers.

### `ledger.csv`
```csv
txn_id,rrn,account,type,amount,debit_date
TXN001,rrn-1001,ACC101,UPI_P2P,500.00,2026-10-01
```

### `switch.csv`
```csv
rrn,status,amount,date
RRN1001,SUCCESS,500.00,2026-10-01
```

### `reversals.csv`
```csv
rrn,amount,reversal_date
rrn 1002,1200.00,2026-10-06
```

---

## 4. Turnaround Times & Compensation Calculation

### Turnaround Times (Calendar Days by Default)
| Transaction Type | Default TAT (Calendar Days) | Due Date Formula |
| :--- | :---: | :--- |
| `UPI_P2P` | 1 day | `debit_date + 1 day` |
| `IMPS` | 1 day | `debit_date + 1 day` |
| `UPI_MERCHANT` | 5 days | `debit_date + 5 days` |
| `ATM` | 5 days | `debit_date + 5 days` |
| `CARD_POS` | 5 days | `debit_date + 5 days` |

*Note: Calendar days are used by default (weekends and bank holidays are treated as standard calendar days). Both channel TAT days and the daily compensation rate are configurable via `CompensationCalculator`.*

### Days Late & Compensation Rules
1. **Due Date**: `due_date = debit_date + TAT_days`. A reversal arriving **on** the due date is considered **on time** (`0` days late).
2. **Days Late**:
   - For `RESOLVED_LATE`: `days_late = reversal_date - due_date`.
   - For `BREACH_OPEN`: `days_late = as_of_date - due_date`.
   - For all other outcomes: `0` (never negative).
3. **Illustrative Compensation**:
   - Calculated using `BigDecimal` as `daily_rate × days_late` (default rate: **₹100.00 per late day**).

---

## 5. Reconciliation Algorithm & Rules

1. **Reference Normalization**: Every `rrn` is converted to uppercase and stripped of all non-alphanumeric characters (`" rrn-1001/a "` $\rightarrow$ `"RRN1001A"`).
2. **CSV Validation & Exclusion**: Rows with missing required fields, non-alphanumeric RRNs, malformed ISO dates, zero/negative/non-numeric amounts, unknown transaction types, or invalid switch statuses are rejected with their 1-based line numbers.
   - **Important Note on Excluded Rows**: Rows rejected by CSV validation are excluded from reconciliation. Because they are excluded before matching, a rejected row in one file can cause its counterpart in another file to appear missing (for example, if a ledger row is rejected, its valid switch record will be classified as `MISSING_IN_LEDGER`, or its valid reversal will be classified as `ORPHAN_REVERSAL`).
3. **Single-Pass Grouping**: Valid records from `ledger.csv`, `switch.csv`, and `reversals.csv` are indexed by `normalizedRrn` using `LinkedHashMap` so each unique reference is evaluated exactly once.
4. **Decision Order per Normalized Reference**:
   - **Rule 1 (`DUPLICATE_DEBIT`)**: If more than one ledger entry exists for the normalized reference, report `DUPLICATE_DEBIT` **once** for that reference and **skip all other checks** for that reference.
   - **Rule 10 (`ORPHAN_REVERSAL`)**: If no ledger entry exists and at least one reversal exists, report `ORPHAN_REVERSAL`.
   - **Rule 9 (`MISSING_IN_LEDGER`)**: If no ledger entry exists and a switch record exists, report `MISSING_IN_LEDGER`.
   - **Rule 2 (`MISSING_IN_SWITCH`)**: If a single ledger debit exists but no switch record exists, report `MISSING_IN_SWITCH`.
   - **Switch Status = `SUCCESS` (Rule 3 & Additional Rules 2–3)**:
     - If a reversal exists on a `SUCCESS` switch transaction, report `AMOUNT_MISMATCH` with the explanation note `"reversal on a successful transaction"`.
     - If the ledger amount and switch amount differ (`compareTo != 0`), report `AMOUNT_MISMATCH`.
     - If the ledger and switch amounts match and no reversal exists, report `OK`.
   - **Switch Status = `FAILED` (Rules 4–8 & Additional Rule 4)**:
     - **No Reversal Present**:
       - If ledger and switch amounts differ, report `AMOUNT_MISMATCH`.
       - If `as_of_date <= due_date`, report `PENDING_WITHIN_TAT`.
       - If `as_of_date > due_date`, report `BREACH_OPEN` with `days_late = as_of_date - due_date` and compensation.
     - **One or More Reversals Present**:
       - If multiple reversals exist for the same reference, select the **earliest** reversal by `reversal_date` and append a note stating that `"duplicates were ignored"`.
       - If the reversal amount differs from the ledger debit amount (`compareTo != 0`), report `AMOUNT_MISMATCH`.
       - If `reversal_date <= due_date`, report `RESOLVED_ON_TIME`.
       - If `reversal_date > due_date`, report `RESOLVED_LATE` with `days_late = reversal_date - due_date` and compensation.
   - **Duplicate Switch Records**: If multiple switch records exist for a reference, the earliest switch record by date is used and a note is appended to the explanation.

---

## 6. How to Compile, Test, and Run

### Single Command to Compile, Run All JUnit 5 Tests, and Execute the Application
From inside the `txnrecon` directory:
```powershell
mvn test exec:java
```

### Run Only Unit Tests
```powershell
mvn test
```

### Run with Custom Data Directory or Custom As-Of Date
`Main` accepts optional CLI arguments `[dataDir] [asOfDate]` (default directory: `data`, default as-of date: `2026-10-11`):
```powershell
mvn compile exec:java -Dexec.args="data 2026-10-15"
```
Or using the compiled classes directly with `java`:
```powershell
java -cp target/classes com.txnrecon.Main data 2026-10-11
```

---

## 7. Known Limitations & Assumptions

1. **In-Memory Processing**: All CSV records are held in memory in standard Java collections (`List`, `LinkedHashMap`). For multi-million-row daily files in production, streaming/chunked database or external-sort pipelines would be used.
2. **Calendar-Day TAT**: Turnaround times use calendar days (`LocalDate.plusDays`) rather than bank working days or holiday calendars.
3. **Simple Comma-Separated Parsing**: The CSV parser uses standard comma splitting (`split(",", -1)`) suitable for structured banking feeds without embedded commas inside quoted strings.
4. **Educational Compensation Model**: The flat ₹100/day calculation is an illustrative model and does not incorporate product-specific caps, T+N settlement cut-offs, or dispute arbitration workflows.
