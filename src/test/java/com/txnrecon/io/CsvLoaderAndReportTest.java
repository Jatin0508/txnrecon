package com.txnrecon.io;

import com.txnrecon.Main;
import com.txnrecon.engine.ReconciliationEngine;
import com.txnrecon.model.LedgerEntry;
import com.txnrecon.model.ReconOutcome;
import com.txnrecon.model.ReconciliationResult;
import com.txnrecon.model.ReversalRecord;
import com.txnrecon.model.SwitchRecord;
import com.txnrecon.report.ReportGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CsvLoader}, row validation errors, {@link ReportGenerator},
 * and end-to-end sample data reconciliation.
 */
class CsvLoaderAndReportTest {

    private final CsvLoader loader = new CsvLoader();

    @Test
    void validatesInvalidCsvRowsDatesAmountsTypesAndStatusesWhileKeepingValidRows() throws IOException {
        String ledgerCsv = """
                txn_id,rrn,account,type,amount,debit_date
                TXN1,RRN-1,ACC1,UPI_P2P,100.00,2026-10-01
                ,RRN-2,ACC2,UPI_P2P,100.00,2026-10-01
                TXN3,---,ACC3,UPI_P2P,100.00,2026-10-01
                TXN4,RRN-4,ACC4,WIRE_TRANSFER,100.00,2026-10-01
                TXN5,RRN-5,ACC5,IMPS,-50.00,2026-10-01
                TXN6,RRN-6,ACC6,IMPS,0.00,2026-10-01
                TXN7,RRN-7,ACC7,IMPS,abc,2026-10-01
                TXN8,RRN-8,ACC8,IMPS,100.00,2026-02-30
                TXN9,RRN-9,ACC9,ATM,250.00,2026-10-02
                """;

        CsvLoadResult<LedgerEntry> ledgerResult = loader.loadLedgerFromString(ledgerCsv, "ledger.csv");
        assertEquals(2, ledgerResult.validRecords().size());
        assertEquals(7, ledgerResult.errors().size());

        // Check line numbers of rejected rows (header is line 1, TXN1 is line 2, first bad row is line 3)
        assertEquals(3, ledgerResult.errors().get(0).lineNumber());
        assertEquals(9, ledgerResult.errors().get(6).lineNumber());

        String switchCsv = """
                rrn,status,amount,date
                RRN-1,SUCCESS,100.00,2026-10-01
                RRN-2,UNKNOWN_STATUS,100.00,2026-10-01
                RRN-3,FAILED,100.00
                """;

        CsvLoadResult<SwitchRecord> switchResult = loader.loadSwitchFromString(switchCsv, "switch.csv");
        assertEquals(1, switchResult.validRecords().size());
        assertEquals(2, switchResult.errors().size());
        assertEquals(3, switchResult.errors().get(0).lineNumber());
        assertEquals(4, switchResult.errors().get(1).lineNumber());
    }

    @Test
    void sampleDataCoversEverySingleOutcomeAndSortsMostOverdueBreachesFirst() throws IOException {
        Path dataDir = Path.of("data");
        CsvLoadResult<LedgerEntry> ledgerLoad = loader.loadLedger(dataDir.resolve("ledger.csv"));
        CsvLoadResult<SwitchRecord> switchLoad = loader.loadSwitch(dataDir.resolve("switch.csv"));
        CsvLoadResult<ReversalRecord> reversalLoad = loader.loadReversals(dataDir.resolve("reversals.csv"));

        ReconciliationEngine engine = new ReconciliationEngine();
        List<ReconciliationResult> results = engine.reconcile(
                ledgerLoad.validRecords(),
                switchLoad.validRecords(),
                reversalLoad.validRecords(),
                Main.DEFAULT_AS_OF_DATE
        );

        ReportGenerator reportGenerator = new ReportGenerator();
        Map<ReconOutcome, Long> counts = reportGenerator.countByOutcome(results);

        // Verify that every single one of the 10 outcomes has at least 1 example in sample data
        for (ReconOutcome outcome : ReconOutcome.values()) {
            assertTrue(counts.get(outcome) >= 1L,
                    "Expected sample data to contain at least one example of " + outcome);
        }

        // Verify most overdue breaches are ordered first (RRN1006 has 11 days late, RRN1005 has 4 days late)
        List<ReconciliationResult> breaches = reportGenerator.getOpenBreachesMostOverdueFirst(results);
        assertEquals(2, breaches.size());
        assertEquals("RRN1006", breaches.get(0).normalizedRrn());
        assertEquals(11L, breaches.get(0).daysLate());
        assertEquals(0, new BigDecimal("1100").compareTo(breaches.get(0).compensation()));

        assertEquals("RRN1005", breaches.get(1).normalizedRrn());
        assertEquals(4L, breaches.get(1).daysLate());
        assertEquals(0, new BigDecimal("400").compareTo(breaches.get(1).compensation()));

        // Verify formatted report contains all required sections
        String report = Main.runReconciliation(dataDir, Main.DEFAULT_AS_OF_DATE);
        assertTrue(report.contains("OPEN TAT BREACHES (MOST OVERDUE FIRST)"));
        assertTrue(report.contains("SUMMARY COUNTS BY OUTCOME"));
        assertTrue(report.contains("REJECTED CSV ROWS / VALIDATION ERRORS"));
    }
}
