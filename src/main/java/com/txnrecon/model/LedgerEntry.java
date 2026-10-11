package com.txnrecon.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable record representing a customer debit row from ledger.csv.
 *
 * @param txnId         Bank-internal transaction identifier (e.g., TXN001)
 * @param rrn           Original reference number as written in the CSV
 * @param normalizedRrn Normalized reference (uppercase alphanumeric only) used for matching
 * @param account       Customer account identifier
 * @param type          Payment channel (determines turnaround time)
 * @param amount        Debited amount (stored as BigDecimal to avoid floating-point rounding bugs)
 * @param debitDate     Calendar date the customer account was debited
 */
public record LedgerEntry(
        String txnId,
        String rrn,
        String normalizedRrn,
        String account,
        TransactionType type,
        BigDecimal amount,
        LocalDate debitDate
) {
}
