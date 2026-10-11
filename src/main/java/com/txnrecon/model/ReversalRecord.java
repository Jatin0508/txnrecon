package com.txnrecon.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable record representing a fund reversal row from reversals.csv.
 *
 * @param rrn           Original reference number as written in the CSV
 * @param normalizedRrn Normalized reference (uppercase alphanumeric only) used for matching
 * @param amount        Reversed amount credited back to the customer
 * @param reversalDate  Calendar date the reversal was completed
 */
public record ReversalRecord(
        String rrn,
        String normalizedRrn,
        BigDecimal amount,
        LocalDate reversalDate
) {
}
