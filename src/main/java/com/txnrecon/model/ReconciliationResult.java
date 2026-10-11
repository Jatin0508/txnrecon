package com.txnrecon.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable record representing the reconciliation outcome for a single normalized reference.
 *
 * @param normalizedRrn Normalized transaction reference (RRN)
 * @param outcome       Classified reconciliation outcome
 * @param dueDate       Calculated reversal due date for failed transactions (null if not applicable)
 * @param daysLate      Number of calendar days past dueDate (0 unless RESOLVED_LATE or BREACH_OPEN)
 * @param compensation  Illustrative compensation amount in INR (0 unless RESOLVED_LATE or BREACH_OPEN)
 * @param explanation   Human-readable explanation of why this outcome was chosen
 */
public record ReconciliationResult(
        String normalizedRrn,
        ReconOutcome outcome,
        LocalDate dueDate,
        long daysLate,
        BigDecimal compensation,
        String explanation
) {
}
