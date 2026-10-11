package com.txnrecon.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable record representing a payment-switch status row from switch.csv.
 *
 * @param rrn           Original reference number as written in the CSV
 * @param normalizedRrn Normalized reference (uppercase alphanumeric only) used for matching
 * @param status        Switch status (SUCCESS or FAILED)
 * @param amount        Transaction amount recorded at the switch
 * @param date          Transaction date recorded at the switch
 */
public record SwitchRecord(
        String rrn,
        String normalizedRrn,
        SwitchStatus status,
        BigDecimal amount,
        LocalDate date
) {
}
