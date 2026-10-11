# TxnRecon — Java Interview Preparation Guide

This guide explains the exact code and design decisions in **TxnRecon** so you can walk an interviewer through the project clearly and confidently.

---

## 1. Recommended Study Order (Which Classes to Read First)

If you have 15 minutes before an interview, study the classes in this exact order:

1. **`model/TransactionType.java` & `model/ReconOutcome.java`** — Understand the domain vocabulary: the 5 payment channels, their default TAT days (1 or 5 days), and the 10 reconciliation outcomes.
2. **`model/LedgerEntry.java`, `SwitchRecord.java`, `ReversalRecord.java`, `ReconciliationResult.java`** — See how Java 17 `record` types model immutable data carriers cleanly without boilerplate getters/setters.
3. **`engine/ReferenceNormalizer.java` & `engine/CompensationCalculator.java`** — Short, self-contained classes demonstrating string sanitization, `LocalDate` math, and `BigDecimal` arithmetic.
4. **`engine/ReconciliationEngine.java`** — The heart of the project. Trace `reconcile()` and `reconcileSingleReference()` from top to bottom.
5. **`io/CsvLoader.java` & `exception/CsvValidationException.java`** — Shows how corrupt input rows are isolated and reported with line numbers without crashing the batch job.
6. **`report/ReportGenerator.java` & `Main.java`** — Shows stream sorting (`Comparator`), `EnumMap` aggregation, and CLI entry point handling.

---

## 2. Core Technical Concepts Used in This Codebase

### A. Object-Oriented Programming (OOP) Principles
- **Single Responsibility Principle (SRP)**:
  - `CsvLoader` only parses and validates CSV text into domain objects.
  - `ReferenceNormalizer` only sanitizes reference strings.
  - `CompensationCalculator` only computes dates, overdue days, and monetary compensation.
  - `ReconciliationEngine` only performs 3-way matching and outcome classification.
  - `ReportGenerator` only formats results for display.
- **Immutability**:
  - Every domain model (`LedgerEntry`, `SwitchRecord`, `ReversalRecord`, `ReconciliationResult`, `CsvValidationError`) is a Java `record`. Once constructed, its fields are `final` and cannot be mutated accidentally during reconciliation.
- **Dependency Injection via Constructors**:
  - `ReconciliationEngine` accepts a `CompensationCalculator` in its constructor (with a default no-arg constructor for convenience). This makes it trivial to test custom compensation rates and custom TAT days in unit tests without modifying code.

### B. Enums & Records
- **Why Enums (`TransactionType`, `SwitchStatus`, `ReconOutcome`)?**
  - Enums provide compile-time type safety instead of fragile magic strings like `"SUCCESS"` or `"UPI_P2P"`.
  - In `TransactionType`, each enum constant carries behavior/data (`defaultTatDays`), associating channel configuration directly with the type.
- **Why Java 17 `record`s?**
  - A `record` automatically generates a canonical constructor, `final` fields, accessor methods (`entry.amount()`), and value-based `equals()`, `hashCode()`, and `toString()`, eliminating hundreds of lines of boilerplate.

### C. Collections, Maps, and Duplicate Detection
- **`LinkedHashMap<String, List<...>>` for Grouping**:
  - In `ReconciliationEngine`, we group ledger entries, switch records, and reversals by `normalizedRrn` into `LinkedHashMap`s.
  - Why `LinkedHashMap` instead of `HashMap`? `HashMap` has arbitrary iteration order, whereas `LinkedHashMap` preserves insertion order so our reports and tests are 100% deterministic.
  - Why `List<LedgerEntry>` as the map value instead of a single `LedgerEntry`? Because a key requirement is detecting `DUPLICATE_DEBIT` when `ledgers.size() > 1` and handling multiple reversals (`reversals.size() > 1`).
- **`LinkedHashSet<String>` for the Union of Keys**:
  - `allRefs` collects keys from `ledgerByRef`, `switchByRef`, and `reversalByRef`. Using a `Set` guarantees we emit **at most one `ReconciliationResult` per normalized reference**.
- **`EnumMap<ReconOutcome, Long>` in `ReportGenerator`**:
  - `EnumMap` is backed internally by a compact array indexed by enum ordinal, making it faster and more memory-efficient than `HashMap`, while naturally iterating in the order the enum constants are declared.

### D. `BigDecimal` vs `double` for Money
- **Why never use `double` or `float` for money?**
  - `double` uses binary floating-point representation (IEEE 754), which cannot represent base-10 decimals like `0.1` or `0.01` exactly (`0.1 + 0.2 == 0.30000000000000004`). In financial reconciliation, a 1-ulp rounding error would trigger false `AMOUNT_MISMATCH` alerts.
  - `BigDecimal` stores an arbitrary-precision unscaled integer (`BigInteger`) and a base-10 `scale`, guaranteeing exact decimal math.
- **Critical `BigDecimal` Interview Trap — `compareTo()` vs `equals()`**:
  - `new BigDecimal("500.0").equals(new BigDecimal("500.00"))` returns **`false`** because `equals()` compares both value and `scale` (`1 != 2`).
  - `new BigDecimal("500.0").compareTo(new BigDecimal("500.00")) == 0` returns **`true`** because `compareTo()` compares only numeric value.
  - In `ReconciliationEngine`, we always use `compareTo(...) == 0` when checking whether amounts match!
- **Always construct `BigDecimal` from `String` or `BigDecimal.valueOf(...)`**:
  - In `CsvLoader`, we call `new BigDecimal(String)`—never `new BigDecimal(double)`.

### E. Exception Handling & CSV Validation
- **Custom Unchecked Exception (`CsvValidationException`)**:
  - Carries structured fields: `sourceName`, `lineNumber` (1-based), `rawLine`, and `reason`.
- **Resilient Batch Processing Pattern**:
  - In banking batch jobs, one corrupt line on row 500 out of 100,000 should not crash the entire nightly reconciliation run.
  - `CsvLoader` wraps each row parse in a `try / catch (CsvValidationException)` block, converts failures into `CsvValidationError` records with their exact line numbers, and returns a `CsvLoadResult<T>` containing both `validRecords` and `errors`.

### F. Date & Time Calculations (`java.time`)
- We use `java.time.LocalDate` (immutable, thread-safe, ISO-8601 by default) rather than legacy `java.util.Date` or `Calendar`.
- **Due Date**: `debitDate.plusDays(tatDays)`.
- **Boundary Checks**:
  - On or before due date: `!date.isAfter(dueDate)` (equivalent to `date <= dueDate`).
  - After due date: `date.isAfter(dueDate)` (equivalent to `date > dueDate`).
- **Days Late**: `Math.max(0L, ChronoUnit.DAYS.between(dueDate, comparisonDate))`.

---

## 3. Time and Space Complexity Analysis

Let:
- $L$ = number of valid rows in `ledger.csv`
- $S$ = number of valid rows in `switch.csv`
- $R$ = number of valid rows in `reversals.csv`
- $N = L + S + R$ = total input records
- $U \le N$ = number of unique normalized references

1. **CSV Loading & Reference Normalization**:
   - Reading each row and normalizing an RRN of length $k$ takes $O(k)$ time (where $k \le 20$ is a small constant).
   - Total loading time: **$O(L + S + R) = O(N)$**.
2. **Indexing & Reconciliation (`ReconciliationEngine.reconcile`)**:
   - Grouping into `LinkedHashMap`s takes $O(1)$ average time per record $\rightarrow$ **$O(N)$** total.
   - Iterating over $U$ unique references and matching across the three maps takes $O(1)$ map lookups per reference (plus finding the minimum reversal date if duplicates exist for a single RRN).
   - Total reconciliation time: **$O(N)$** (versus $O(N^2)$ if we had used nested loops across lists!).
3. **Report Sorting (`ReportGenerator`)**:
   - Sorting the $U$ results so the most overdue `BREACH_OPEN` items appear first takes **$O(U \log U)$** time.
4. **Space Complexity**:
   - Storing the input lists, lookup maps, and result list in memory requires **$O(N)$** auxiliary space.

---

## 4. Likely Interview Questions & Model Answers

### Q1: "Why did you use `LinkedHashMap` instead of nested `for` loops to match ledger, switch, and reversal records?"
> **Answer**: Nested loops comparing $L$ ledger entries against $S$ switch records and $R$ reversals would take $O(L \times S + L \times R) \approx O(N^2)$ time. By indexing each list into a `LinkedHashMap` keyed by the normalized RRN, lookup is $O(1)$ on average, bringing the entire 3-way reconciliation down to linear $O(L + S + R)$ time while preserving deterministic file order.

### Q2: "What happens if the same reference has two ledger debits AND is also missing in the switch?"
> **Answer**: We explicitly check `ledgers.size() > 1` first. If a reference has duplicate ledger debits, the engine classifies it once as `DUPLICATE_DEBIT` and immediately returns, skipping all downstream switch and reversal checks for that reference so the same transaction is never double-reported.

### Q3: "How does your engine handle multiple reversals for the same failed transaction?"
> **Answer**: We select the earliest reversal using `reversals.stream().min(Comparator.comparing(ReversalRecord::reversalDate))` to evaluate whether the customer was refunded within TAT, and we append a note to the explanation stating that multiple reversals were found and duplicates were ignored.

### Q4: "What if a switch record says `SUCCESS`, the amounts match, but there is also a row in `reversals.csv`?"
> **Answer**: Before reporting `OK` on a `SUCCESS` switch record, our engine checks whether any reversal exists for that reference. If a reversal exists on a successful transaction, it flags an `AMOUNT_MISMATCH` with the explanation `"reversal on a successful transaction"` because a completed payment should not also be auto-reversed without a book adjustment.

### Q5: "What happens to a transaction if its ledger row is rejected by CSV validation (e.g., due to a malformed date), but its switch row is valid?"
> **Answer**: Rejected CSV rows are logged with their line numbers and excluded from reconciliation. Because the invalid ledger row is excluded, the valid switch row will have no matching ledger entry during reconciliation and will be classified as `MISSING_IN_LEDGER`. We document this behavior in the README so operators check the CSV Validation Errors section alongside missing-record alerts.
