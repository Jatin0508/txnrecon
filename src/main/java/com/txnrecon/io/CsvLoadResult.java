package com.txnrecon.io;

import java.util.List;

/**
 * Container holding both the valid parsed records and any row-level validation errors
 * encountered while reading a CSV file.
 *
 * @param <T>          Record type (LedgerEntry, SwitchRecord, or ReversalRecord)
 * @param validRecords List of rows that passed validation
 * @param errors       List of rejected rows with line numbers and failure reasons
 */
public record CsvLoadResult<T>(
        List<T> validRecords,
        List<CsvValidationError> errors
) {
}
