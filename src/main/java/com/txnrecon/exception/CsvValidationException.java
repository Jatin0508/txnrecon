package com.txnrecon.exception;

/**
 * Exception thrown when a single CSV row fails structural or domain validation
 * (such as missing required fields, malformed ISO dates, non-positive amounts,
 * unknown transaction types, or invalid switch statuses).
 */
public class CsvValidationException extends RuntimeException {

    private final String sourceName;
    private final int lineNumber;
    private final String rawLine;
    private final String reason;

    public CsvValidationException(String sourceName, int lineNumber, String rawLine, String reason) {
        super(String.format("%s [line %d]: %s (row: \"%s\")", sourceName, lineNumber, reason, rawLine));
        this.sourceName = sourceName;
        this.lineNumber = lineNumber;
        this.rawLine = rawLine;
        this.reason = reason;
    }

    public CsvValidationException(String sourceName, int lineNumber, String rawLine, String reason, Throwable cause) {
        super(String.format("%s [line %d]: %s (row: \"%s\")", sourceName, lineNumber, reason, rawLine), cause);
        this.sourceName = sourceName;
        this.lineNumber = lineNumber;
        this.rawLine = rawLine;
        this.reason = reason;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getRawLine() {
        return rawLine;
    }

    public String getReason() {
        return reason;
    }
}
