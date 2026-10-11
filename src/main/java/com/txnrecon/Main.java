package com.txnrecon;

import com.txnrecon.engine.CompensationCalculator;
import com.txnrecon.engine.ReconciliationEngine;
import com.txnrecon.io.CsvLoadResult;
import com.txnrecon.io.CsvLoader;
import com.txnrecon.io.CsvValidationError;
import com.txnrecon.model.LedgerEntry;
import com.txnrecon.model.ReconciliationResult;
import com.txnrecon.model.ReversalRecord;
import com.txnrecon.model.SwitchRecord;
import com.txnrecon.report.ReportGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point for TxnRecon — Digital Transaction Reconciliation Engine.
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/classes com.txnrecon.Main [dataDir] [asOfDate]
 * </pre>
 * Defaults to directory {@code data} and as-of date {@code 2026-10-11} for repeatable output.
 */
public class Main {

    public static final LocalDate DEFAULT_AS_OF_DATE = LocalDate.of(2026, 10, 11);
    public static final String DEFAULT_DATA_DIR = "data";

    public static void main(String[] args) {
        Path dataDir = resolveDefaultDataDir();
        LocalDate asOfDate = DEFAULT_AS_OF_DATE;

        if (args.length == 1) {
            // Allow single argument to be either an ISO date (yyyy-MM-dd) or a directory path
            LocalDate parsedDate = tryParseDate(args[0]);
            if (parsedDate != null) {
                asOfDate = parsedDate;
            } else {
                dataDir = Path.of(args[0]);
            }
        } else if (args.length >= 2) {
            dataDir = Path.of(args[0]);
            LocalDate parsedDate = tryParseDate(args[1]);
            if (parsedDate == null) {
                System.err.println("Invalid as-of date '" + args[1] + "'. Expected ISO format yyyy-MM-dd.");
                System.exit(1);
                return;
            }
            asOfDate = parsedDate;
        }

        try {
            String report = runReconciliation(dataDir, asOfDate);
            System.out.print(report);
        } catch (IOException ex) {
            System.err.println("Failed to read input CSV files from directory '" + dataDir.toAbsolutePath() + "': " + ex.getMessage());
            System.exit(1);
        }
    }

    /**
     * Loads CSV files from {@code dataDir}, executes the reconciliation engine for {@code asOfDate},
     * and returns the formatted report string.
     */
    public static String runReconciliation(Path dataDir, LocalDate asOfDate) throws IOException {
        CsvLoader loader = new CsvLoader();

        Path ledgerPath = dataDir.resolve("ledger.csv");
        Path switchPath = dataDir.resolve("switch.csv");
        Path reversalsPath = dataDir.resolve("reversals.csv");

        CsvLoadResult<LedgerEntry> ledgerLoad = loader.loadLedger(ledgerPath);
        CsvLoadResult<SwitchRecord> switchLoad = loader.loadSwitch(switchPath);
        CsvLoadResult<ReversalRecord> reversalLoad = loader.loadReversals(reversalsPath);

        List<CsvValidationError> allErrors = new ArrayList<>();
        allErrors.addAll(ledgerLoad.errors());
        allErrors.addAll(switchLoad.errors());
        allErrors.addAll(reversalLoad.errors());

        CompensationCalculator calculator = new CompensationCalculator();
        ReconciliationEngine engine = new ReconciliationEngine(calculator);

        List<ReconciliationResult> results = engine.reconcile(
                ledgerLoad.validRecords(),
                switchLoad.validRecords(),
                reversalLoad.validRecords(),
                asOfDate
        );

        ReportGenerator reportGenerator = new ReportGenerator();
        return reportGenerator.generateReport(results, allErrors, asOfDate, calculator.getDailyRate());
    }

    private static Path resolveDefaultDataDir() {
        Path direct = Path.of(DEFAULT_DATA_DIR);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path nested = Path.of("txnrecon", DEFAULT_DATA_DIR);
        if (Files.isDirectory(nested)) {
            return nested;
        }
        return direct;
    }

    private static LocalDate tryParseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
