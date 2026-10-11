package com.txnrecon.io;

import com.txnrecon.engine.ReferenceNormalizer;
import com.txnrecon.exception.CsvValidationException;
import com.txnrecon.model.LedgerEntry;
import com.txnrecon.model.ReversalRecord;
import com.txnrecon.model.SwitchRecord;
import com.txnrecon.model.SwitchStatus;
import com.txnrecon.model.TransactionType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads and validates ledger.csv, switch.csv, and reversals.csv files.
 *
 * <p>Rows that fail validation (missing fields, malformed dates, zero/negative amounts,
 * unknown transaction types, or invalid statuses) are captured as {@link CsvValidationError}
 * objects with their 1-based line numbers, and excluded from reconciliation while valid rows
 * continue to be processed.
 */
public class CsvLoader {

    private static final String LEDGER_HEADER = "txn_id,rrn,account,type,amount,debit_date";
    private static final String SWITCH_HEADER = "rrn,status,amount,date";
    private static final String REVERSALS_HEADER = "rrn,amount,reversal_date";

    public CsvLoadResult<LedgerEntry> loadLedger(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return loadLedger(reader, filePath.getFileName().toString());
        }
    }

    public CsvLoadResult<LedgerEntry> loadLedgerFromString(String csvContent, String sourceName) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            return loadLedger(reader, sourceName);
        }
    }

    public CsvLoadResult<LedgerEntry> loadLedger(BufferedReader reader, String sourceName) throws IOException {
        return loadGeneric(reader, sourceName, LEDGER_HEADER, this::parseLedgerRow);
    }

    public CsvLoadResult<SwitchRecord> loadSwitch(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return loadSwitch(reader, filePath.getFileName().toString());
        }
    }

    public CsvLoadResult<SwitchRecord> loadSwitchFromString(String csvContent, String sourceName) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            return loadSwitch(reader, sourceName);
        }
    }

    public CsvLoadResult<SwitchRecord> loadSwitch(BufferedReader reader, String sourceName) throws IOException {
        return loadGeneric(reader, sourceName, SWITCH_HEADER, this::parseSwitchRow);
    }

    public CsvLoadResult<ReversalRecord> loadReversals(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return loadReversals(reader, filePath.getFileName().toString());
        }
    }

    public CsvLoadResult<ReversalRecord> loadReversalsFromString(String csvContent, String sourceName) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            return loadReversals(reader, sourceName);
        }
    }

    public CsvLoadResult<ReversalRecord> loadReversals(BufferedReader reader, String sourceName) throws IOException {
        return loadGeneric(reader, sourceName, REVERSALS_HEADER, this::parseReversalRow);
    }

    /**
     * Parses and validates a single ledger.csv row: txn_id,rrn,account,type,amount,debit_date.
     */
    public LedgerEntry parseLedgerRow(String rawLine, int lineNumber, String sourceName) {
        String[] cols = splitColumns(rawLine, 6, lineNumber, sourceName);
        String txnId = requireField(cols[0], "txn_id", rawLine, lineNumber, sourceName);
        String rrn = requireField(cols[1], "rrn", rawLine, lineNumber, sourceName);
        String normalizedRrn = requireNormalizedRrn(rrn, rawLine, lineNumber, sourceName);
        String account = requireField(cols[2], "account", rawLine, lineNumber, sourceName);
        TransactionType type = parseTransactionType(cols[3], rawLine, lineNumber, sourceName);
        BigDecimal amount = parsePositiveAmount(cols[4], "amount", rawLine, lineNumber, sourceName);
        LocalDate debitDate = parseIsoDate(cols[5], "debit_date", rawLine, lineNumber, sourceName);

        return new LedgerEntry(txnId, rrn, normalizedRrn, account, type, amount, debitDate);
    }

    /**
     * Parses and validates a single switch.csv row: rrn,status,amount,date.
     */
    public SwitchRecord parseSwitchRow(String rawLine, int lineNumber, String sourceName) {
        String[] cols = splitColumns(rawLine, 4, lineNumber, sourceName);
        String rrn = requireField(cols[0], "rrn", rawLine, lineNumber, sourceName);
        String normalizedRrn = requireNormalizedRrn(rrn, rawLine, lineNumber, sourceName);
        SwitchStatus status = parseSwitchStatus(cols[1], rawLine, lineNumber, sourceName);
        BigDecimal amount = parsePositiveAmount(cols[2], "amount", rawLine, lineNumber, sourceName);
        LocalDate date = parseIsoDate(cols[3], "date", rawLine, lineNumber, sourceName);

        return new SwitchRecord(rrn, normalizedRrn, status, amount, date);
    }

    /**
     * Parses and validates a single reversals.csv row: rrn,amount,reversal_date.
     */
    public ReversalRecord parseReversalRow(String rawLine, int lineNumber, String sourceName) {
        String[] cols = splitColumns(rawLine, 3, lineNumber, sourceName);
        String rrn = requireField(cols[0], "rrn", rawLine, lineNumber, sourceName);
        String normalizedRrn = requireNormalizedRrn(rrn, rawLine, lineNumber, sourceName);
        BigDecimal amount = parsePositiveAmount(cols[1], "amount", rawLine, lineNumber, sourceName);
        LocalDate reversalDate = parseIsoDate(cols[2], "reversal_date", rawLine, lineNumber, sourceName);

        return new ReversalRecord(rrn, normalizedRrn, amount, reversalDate);
    }

    @FunctionalInterface
    private interface RowParser<T> {
        T parse(String rawLine, int lineNumber, String sourceName);
    }

    private <T> CsvLoadResult<T> loadGeneric(
            BufferedReader reader,
            String sourceName,
            String expectedHeader,
            RowParser<T> rowParser
    ) throws IOException {
        List<T> validRecords = new ArrayList<>();
        List<CsvValidationError> errors = new ArrayList<>();

        String headerLine = reader.readLine();
        if (headerLine == null) {
            errors.add(new CsvValidationError(sourceName, 1, "", "CSV file is empty; expected header: " + expectedHeader));
            return new CsvLoadResult<>(validRecords, errors);
        }

        // Strip UTF-8 BOM if present at the start of the header
        String cleanedHeader = headerLine.replace("\uFEFF", "").trim();
        if (!cleanedHeader.equalsIgnoreCase(expectedHeader)) {
            errors.add(new CsvValidationError(
                    sourceName, 1, headerLine,
                    "Unexpected CSV header. Expected '" + expectedHeader + "' but found '" + cleanedHeader + "'"));
            return new CsvLoadResult<>(validRecords, errors);
        }

        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                errors.add(new CsvValidationError(sourceName, lineNumber, line, "Empty row is not allowed"));
                continue;
            }
            try {
                T record = rowParser.parse(line, lineNumber, sourceName);
                validRecords.add(record);
            } catch (CsvValidationException ex) {
                errors.add(CsvValidationError.fromException(ex));
            }
        }

        return new CsvLoadResult<>(validRecords, errors);
    }

    private String[] splitColumns(String rawLine, int expectedColumns, int lineNumber, String sourceName) {
        if (rawLine == null || rawLine.trim().isEmpty()) {
            throw new CsvValidationException(sourceName, lineNumber, rawLine == null ? "" : rawLine, "Row is empty");
        }
        String[] parts = rawLine.split(",", -1);
        if (parts.length != expectedColumns) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    String.format("Expected %d columns but found %d", expectedColumns, parts.length));
        }
        return parts;
    }

    private String requireField(String value, String fieldName, String rawLine, int lineNumber, String sourceName) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Missing required field '" + fieldName + "'");
        }
        return trimmed;
    }

    private String requireNormalizedRrn(String rrn, String rawLine, int lineNumber, String sourceName) {
        String normalized = ReferenceNormalizer.normalize(rrn);
        if (normalized.isEmpty()) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Field 'rrn' contains no alphanumeric characters after normalization: '" + rrn + "'");
        }
        return normalized;
    }

    private TransactionType parseTransactionType(String rawType, String rawLine, int lineNumber, String sourceName) {
        String value = requireField(rawType, "type", rawLine, lineNumber, sourceName);
        try {
            return TransactionType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Unknown transaction type '" + value + "'", ex);
        }
    }

    private SwitchStatus parseSwitchStatus(String rawStatus, String rawLine, int lineNumber, String sourceName) {
        String value = requireField(rawStatus, "status", rawLine, lineNumber, sourceName);
        try {
            return SwitchStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Invalid switch status '" + value + "' (expected SUCCESS or FAILED)", ex);
        }
    }

    private BigDecimal parsePositiveAmount(String rawAmount, String fieldName, String rawLine, int lineNumber, String sourceName) {
        String value = requireField(rawAmount, fieldName, rawLine, lineNumber, sourceName);
        final BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Malformed numeric amount in '" + fieldName + "': '" + value + "'", ex);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Amount in '" + fieldName + "' must be positive, found: " + value);
        }
        return amount;
    }

    private LocalDate parseIsoDate(String rawDate, String fieldName, String rawLine, int lineNumber, String sourceName) {
        String value = requireField(rawDate, fieldName, rawLine, lineNumber, sourceName);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new CsvValidationException(
                    sourceName, lineNumber, rawLine,
                    "Malformed ISO date in '" + fieldName + "' (expected yyyy-MM-dd): '" + value + "'", ex);
        }
    }
}
