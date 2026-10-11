package com.txnrecon.io;

import com.txnrecon.exception.CsvValidationException;

/**
 * Immutable record capturing details of a rejected CSV row so that valid rows
 * can continue processing while validation errors are reported clearly.
 *
 * @param sourceName File or stream name (e.g., "ledger.csv")
 * @param lineNumber 1-based line number in the CSV file (header is line 1)
 * @param rawLine    Original text of the rejected line
 * @param reason     Explanation of why validation failed
 */
public record CsvValidationError(
        String sourceName,
        int lineNumber,
        String rawLine,
        String reason
) {
    public static CsvValidationError fromException(CsvValidationException ex) {
        return new CsvValidationError(
                ex.getSourceName(),
                ex.getLineNumber(),
                ex.getRawLine(),
                ex.getReason()
        );
    }
}
