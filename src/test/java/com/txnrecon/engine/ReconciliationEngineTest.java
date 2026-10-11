package com.txnrecon.engine;

import com.txnrecon.model.LedgerEntry;
import com.txnrecon.model.ReconOutcome;
import com.txnrecon.model.ReconciliationResult;
import com.txnrecon.model.ReversalRecord;
import com.txnrecon.model.SwitchRecord;
import com.txnrecon.model.SwitchStatus;
import com.txnrecon.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive unit tests for {@link ReconciliationEngine} covering all 10 reconciliation
 * outcomes, boundary cases on the due date, duplicate handling, and additional rules.
 */
class ReconciliationEngineTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 11);
    private ReconciliationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ReconciliationEngine();
    }

    @Test
    void successfulTransactionWithMatchingAmountReturnsOk() {
        LedgerEntry ledger = ledger("TXN1", "rrn-101", TransactionType.UPI_P2P, "500.00", "2026-10-01");
        SwitchRecord sw = switchRec("RRN101", SwitchStatus.SUCCESS, "500.0", "2026-10-01");

        List<ReconciliationResult> results = engine.reconcile(List.of(ledger), List.of(sw), List.of(), AS_OF);

        assertEquals(1, results.size());
        ReconciliationResult r = results.get(0);
        assertEquals("RRN101", r.normalizedRrn());
        assertEquals(ReconOutcome.OK, r.outcome());
        assertNull(r.dueDate());
        assertEquals(0L, r.daysLate());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.compensation()));
    }

    @Test
    void successfulTransactionWithDifferentSwitchAmountReturnsAmountMismatch() {
        LedgerEntry ledger = ledger("TXN1", "RRN102", TransactionType.IMPS, "500.00", "2026-10-01");
        SwitchRecord sw = switchRec("RRN102", SwitchStatus.SUCCESS, "450.00", "2026-10-01");

        List<ReconciliationResult> results = engine.reconcile(List.of(ledger), List.of(sw), List.of(), AS_OF);

        assertEquals(1, results.size());
        assertEquals(ReconOutcome.AMOUNT_MISMATCH, results.get(0).outcome());
    }

    @Test
    void successfulTransactionWithReversalReturnsAmountMismatchWithSpecificNote() {
        LedgerEntry ledger = ledger("TXN1", "RRN103", TransactionType.CARD_POS, "1000.00", "2026-10-01");
        SwitchRecord sw = switchRec("RRN103", SwitchStatus.SUCCESS, "1000.00", "2026-10-01");
        ReversalRecord rev = reversal("RRN103", "1000.00", "2026-10-02");

        List<ReconciliationResult> results = engine.reconcile(List.of(ledger), List.of(sw), List.of(rev), AS_OF);

        assertEquals(1, results.size());
        ReconciliationResult r = results.get(0);
        assertEquals(ReconOutcome.AMOUNT_MISMATCH, r.outcome());
        assertTrue(r.explanation().toLowerCase().contains("reversal on a successful transaction"),
                "Expected explanation to contain 'reversal on a successful transaction' but got: " + r.explanation());
    }

    @Test
    void failedTransactionReversedBeforeAndOnDueDateReturnsResolvedOnTime() {
        // ATM has 5-day TAT: debit 2026-10-01 -> due 2026-10-06
        LedgerEntry beforeDueLedger = ledger("TXN1", "RRN201", TransactionType.ATM, "2000.00", "2026-10-01");
        SwitchRecord beforeDueSw = switchRec("RRN201", SwitchStatus.FAILED, "2000.00", "2026-10-01");
        ReversalRecord beforeDueRev = reversal("RRN201", "2000.00", "2026-10-04");

        // Boundary case: reversal ON the due date (2026-10-06)
        LedgerEntry onDueLedger = ledger("TXN2", "RRN202", TransactionType.ATM, "2000.00", "2026-10-01");
        SwitchRecord onDueSw = switchRec("RRN202", SwitchStatus.FAILED, "2000.00", "2026-10-01");
        ReversalRecord onDueRev = reversal("RRN202", "2000.00", "2026-10-06");

        List<ReconciliationResult> results = engine.reconcile(
                List.of(beforeDueLedger, onDueLedger),
                List.of(beforeDueSw, onDueSw),
                List.of(beforeDueRev, onDueRev),
                AS_OF
        );

        assertEquals(2, results.size());
        for (ReconciliationResult r : results) {
            assertEquals(ReconOutcome.RESOLVED_ON_TIME, r.outcome());
            assertEquals(LocalDate.of(2026, 10, 6), r.dueDate());
            assertEquals(0L, r.daysLate());
            assertEquals(0, BigDecimal.ZERO.compareTo(r.compensation()));
        }
    }

    @Test
    void failedTransactionReversedAfterDueDateReturnsResolvedLateAndCompensation() {
        // UPI_P2P has 1-day TAT: debit 2026-10-01 -> due 2026-10-02; reversed 2026-10-05 -> 3 days late -> ₹300
        LedgerEntry ledger = ledger("TXN1", "RRN301", TransactionType.UPI_P2P, "750.00", "2026-10-01");
        SwitchRecord sw = switchRec("RRN301", SwitchStatus.FAILED, "750.00", "2026-10-01");
        ReversalRecord rev = reversal("RRN301", "750.00", "2026-10-05");

        List<ReconciliationResult> results = engine.reconcile(List.of(ledger), List.of(sw), List.of(rev), AS_OF);

        assertEquals(1, results.size());
        ReconciliationResult r = results.get(0);
        assertEquals(ReconOutcome.RESOLVED_LATE, r.outcome());
        assertEquals(LocalDate.of(2026, 10, 2), r.dueDate());
        assertEquals(3L, r.daysLate());
        assertEquals(0, new BigDecimal("300").compareTo(r.compensation()));
    }

    @Test
    void multipleReversalsUsesEarliestAndAddsNoteAboutIgnoredDuplicates() {
        // IMPS has 1-day TAT: debit 2026-10-05 -> due 2026-10-06
        // Reversal 1: 2026-10-09 (3 days late), Reversal 2: 2026-10-06 (on time, earliest!)
        LedgerEntry ledger = ledger("TXN1", "RRN302", TransactionType.IMPS, "900.00", "2026-10-05");
        SwitchRecord sw = switchRec("RRN302", SwitchStatus.FAILED, "900.00", "2026-10-05");
        ReversalRecord laterRev = reversal("RRN302", "900.00", "2026-10-09");
        ReversalRecord earlierRev = reversal("RRN-302", "900.00", "2026-10-06");

        List<ReconciliationResult> results = engine.reconcile(
                List.of(ledger), List.of(sw), List.of(laterRev, earlierRev), AS_OF);

        assertEquals(1, results.size());
        ReconciliationResult r = results.get(0);
        assertEquals(ReconOutcome.RESOLVED_ON_TIME, r.outcome());
        assertEquals(0L, r.daysLate());
        assertTrue(r.explanation().toLowerCase().contains("duplicates were ignored"),
                "Expected note mentioning 'duplicates were ignored' but got: " + r.explanation());
    }

    @Test
    void failedTransactionWithoutReversalBoundaryOnAndAfterDueDate() {
        // UPI_MERCHANT has 5-day TAT:
        // 1) debit 2026-10-06 -> due 2026-10-11 (equals AS_OF 2026-10-11) -> PENDING_WITHIN_TAT
        // 2) debit 2026-10-05 -> due 2026-10-10 (1 day before AS_OF 2026-10-11) -> BREACH_OPEN (1 day late, ₹100)
        LedgerEntry pendingOnDue = ledger("TXN1", "RRN401", TransactionType.UPI_MERCHANT, "1500.00", "2026-10-06");
        SwitchRecord sw1 = switchRec("RRN401", SwitchStatus.FAILED, "1500.00", "2026-10-06");

        LedgerEntry breachOneDay = ledger("TXN2", "RRN402", TransactionType.UPI_MERCHANT, "1500.00", "2026-10-05");
        SwitchRecord sw2 = switchRec("RRN402", SwitchStatus.FAILED, "1500.00", "2026-10-05");

        List<ReconciliationResult> results = engine.reconcile(
                List.of(pendingOnDue, breachOneDay),
                List.of(sw1, sw2),
                List.of(),
                AS_OF
        );

        ReconciliationResult r1 = results.get(0);
        assertEquals(ReconOutcome.PENDING_WITHIN_TAT, r1.outcome());
        assertEquals(LocalDate.of(2026, 10, 11), r1.dueDate());
        assertEquals(0L, r1.daysLate());
        assertEquals(0, BigDecimal.ZERO.compareTo(r1.compensation()));

        ReconciliationResult r2 = results.get(1);
        assertEquals(ReconOutcome.BREACH_OPEN, r2.outcome());
        assertEquals(LocalDate.of(2026, 10, 10), r2.dueDate());
        assertEquals(1L, r2.daysLate());
        assertEquals(0, new BigDecimal("100").compareTo(r2.compensation()));
    }

    @Test
    void failedTransactionWithMismatchedReversalAmountReturnsAmountMismatch() {
        LedgerEntry ledger = ledger("TXN1", "RRN501", TransactionType.CARD_POS, "1000.00", "2026-10-01");
        SwitchRecord sw = switchRec("RRN501", SwitchStatus.FAILED, "1000.00", "2026-10-01");
        ReversalRecord rev = reversal("RRN501", "800.00", "2026-10-03");

        List<ReconciliationResult> results = engine.reconcile(List.of(ledger), List.of(sw), List.of(rev), AS_OF);

        assertEquals(1, results.size());
        assertEquals(ReconOutcome.AMOUNT_MISMATCH, results.get(0).outcome());
        assertEquals(0L, results.get(0).daysLate());
        assertEquals(0, BigDecimal.ZERO.compareTo(results.get(0).compensation()));
    }

    @Test
    void duplicateDebitReportsOnceAndSkipsAllOtherChecks() {
        LedgerEntry debit1 = ledger("TXN1", "RRN-601", TransactionType.UPI_P2P, "300.00", "2026-10-01");
        LedgerEntry debit2 = ledger("TXN2", "rrn 601", TransactionType.UPI_P2P, "300.00", "2026-10-01");
        // Even without a switch record or with a mismatched reversal, DUPLICATE_DEBIT wins and skips other checks
        ReversalRecord rev = reversal("RRN601", "100.00", "2026-10-10");

        List<ReconciliationResult> results = engine.reconcile(
                List.of(debit1, debit2), List.of(), List.of(rev), AS_OF);

        assertEquals(1, results.size());
        assertEquals("RRN601", results.get(0).normalizedRrn());
        assertEquals(ReconOutcome.DUPLICATE_DEBIT, results.get(0).outcome());
    }

    @Test
    void detectsMissingInSwitchMissingInLedgerAndOrphanReversal() {
        LedgerEntry onlyInLedger = ledger("TXN1", "RRN701", TransactionType.IMPS, "250.00", "2026-10-08");
        SwitchRecord onlyInSwitch = switchRec("RRN702", SwitchStatus.SUCCESS, "600.00", "2026-10-08");
        ReversalRecord onlyInReversals = reversal("RRN703", "400.00", "2026-10-09");

        List<ReconciliationResult> results = engine.reconcile(
                List.of(onlyInLedger),
                List.of(onlyInSwitch),
                List.of(onlyInReversals),
                AS_OF
        );

        assertEquals(3, results.size());
        assertEquals(ReconOutcome.MISSING_IN_SWITCH, results.get(0).outcome());
        assertEquals(ReconOutcome.MISSING_IN_LEDGER, results.get(1).outcome());
        assertEquals(ReconOutcome.ORPHAN_REVERSAL, results.get(2).outcome());
    }

    private LedgerEntry ledger(String txnId, String rrn, TransactionType type, String amount, String date) {
        return new LedgerEntry(
                txnId, rrn, ReferenceNormalizer.normalize(rrn),
                "ACC001", type, new BigDecimal(amount), LocalDate.parse(date));
    }

    private SwitchRecord switchRec(String rrn, SwitchStatus status, String amount, String date) {
        return new SwitchRecord(
                rrn, ReferenceNormalizer.normalize(rrn),
                status, new BigDecimal(amount), LocalDate.parse(date));
    }

    private ReversalRecord reversal(String rrn, String amount, String date) {
        return new ReversalRecord(
                rrn, ReferenceNormalizer.normalize(rrn),
                new BigDecimal(amount), LocalDate.parse(date));
    }
}
