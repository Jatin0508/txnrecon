package com.txnrecon.engine;

import com.txnrecon.model.LedgerEntry;
import com.txnrecon.model.ReconOutcome;
import com.txnrecon.model.ReconciliationResult;
import com.txnrecon.model.ReversalRecord;
import com.txnrecon.model.SwitchRecord;
import com.txnrecon.model.SwitchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core reconciliation engine that compares bank ledger debits, payment-switch statuses,
 * and reversal records to classify each normalized transaction reference into a single outcome.
 *
 * <p>Key design choices:
 * <ul>
 *   <li>Uses {@link LinkedHashMap} and {@link LinkedHashSet} to index records by normalized RRN
 *       in O(N) time while preserving deterministic input order.</li>
 *   <li>Evaluates duplicate debits first and skips all remaining checks for that reference.</li>
 *   <li>Uses {@link BigDecimal#compareTo(BigDecimal)} rather than {@code equals} when comparing
 *       monetary amounts so that scale differences (e.g., "500.0" vs "500.00") do not cause
 *       false mismatches.</li>
 * </ul>
 */
public class ReconciliationEngine {

    private final CompensationCalculator compensationCalculator;

    public ReconciliationEngine() {
        this(new CompensationCalculator());
    }

    public ReconciliationEngine(CompensationCalculator compensationCalculator) {
        this.compensationCalculator = Objects.requireNonNull(
                compensationCalculator, "compensationCalculator must not be null");
    }

    /**
     * Reconciles ledger entries, switch records, and reversal records as of the given date.
     *
     * @param ledgerEntries   Valid customer debit entries from ledger.csv
     * @param switchRecords   Valid payment-switch records from switch.csv
     * @param reversalRecords Valid fund reversal records from reversals.csv
     * @param asOfDate        Cut-off date used to evaluate open breaches vs pending within TAT
     * @return List of reconciliation results (one result per unique normalized reference)
     */
    public List<ReconciliationResult> reconcile(
            List<LedgerEntry> ledgerEntries,
            List<SwitchRecord> switchRecords,
            List<ReversalRecord> reversalRecords,
            LocalDate asOfDate
    ) {
        Objects.requireNonNull(ledgerEntries, "ledgerEntries must not be null");
        Objects.requireNonNull(switchRecords, "switchRecords must not be null");
        Objects.requireNonNull(reversalRecords, "reversalRecords must not be null");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");

        Map<String, List<LedgerEntry>> ledgerByRef = groupByRefLedger(ledgerEntries);
        Map<String, List<SwitchRecord>> switchByRef = groupByRefSwitch(switchRecords);
        Map<String, List<ReversalRecord>> reversalByRef = groupByRefReversal(reversalRecords);

        // Collect all unique normalized references across all three sources in deterministic order
        Set<String> allRefs = new LinkedHashSet<>();
        allRefs.addAll(ledgerByRef.keySet());
        allRefs.addAll(switchByRef.keySet());
        allRefs.addAll(reversalByRef.keySet());

        List<ReconciliationResult> results = new ArrayList<>(allRefs.size());
        for (String ref : allRefs) {
            List<LedgerEntry> ledgers = ledgerByRef.getOrDefault(ref, List.of());
            List<SwitchRecord> switches = switchByRef.getOrDefault(ref, List.of());
            List<ReversalRecord> reversals = reversalByRef.getOrDefault(ref, List.of());

            results.add(reconcileSingleReference(ref, ledgers, switches, reversals, asOfDate));
        }

        return results;
    }

    private ReconciliationResult reconcileSingleReference(
            String ref,
            List<LedgerEntry> ledgers,
            List<SwitchRecord> switches,
            List<ReversalRecord> reversals,
            LocalDate asOfDate
    ) {
        // Rule 1 & Additional Rule 5: More than one ledger debit for a normalized reference
        // means DUPLICATE_DEBIT. Report once per reference and skip all other checks.
        if (ledgers.size() > 1) {
            String explanation = String.format(
                    "Duplicate ledger debits detected (%d entries for reference %s); all other checks skipped.",
                    ledgers.size(), ref);
            return new ReconciliationResult(ref, ReconOutcome.DUPLICATE_DEBIT, null, 0L, BigDecimal.ZERO, explanation);
        }

        // Handle references that do NOT exist in the bank ledger
        if (ledgers.isEmpty()) {
            // Rule 10: A reversal without a matching ledger entry means ORPHAN_REVERSAL
            if (!reversals.isEmpty()) {
                ReversalRecord earliestReversal = selectEarliestReversal(reversals);
                String explanation = String.format(
                        "Reversal of %s on %s has no matching ledger debit.",
                        earliestReversal.amount(), earliestReversal.reversalDate());
                explanation = appendDuplicateNotes(explanation, switches.size(), reversals.size());
                return new ReconciliationResult(ref, ReconOutcome.ORPHAN_REVERSAL, null, 0L, BigDecimal.ZERO, explanation);
            }

            // Rule 9: A switch record without a ledger entry means MISSING_IN_LEDGER
            SwitchRecord switchRecord = selectEarliestSwitch(switches);
            String explanation = String.format(
                    "Switch record (%s, amount %s on %s) has no matching ledger debit.",
                    switchRecord.status(), switchRecord.amount(), switchRecord.date());
            explanation = appendDuplicateNotes(explanation, switches.size(), 0);
            return new ReconciliationResult(ref, ReconOutcome.MISSING_IN_LEDGER, null, 0L, BigDecimal.ZERO, explanation);
        }

        // Exactly one ledger debit exists
        LedgerEntry ledger = ledgers.get(0);

        // Rule 2: A ledger debit without a corresponding switch record means MISSING_IN_SWITCH
        if (switches.isEmpty()) {
            String explanation = String.format(
                    "Ledger debit %s (%s, amount %s on %s) has no matching switch record.",
                    ledger.txnId(), ledger.type(), ledger.amount(), ledger.debitDate());
            explanation = appendDuplicateNotes(explanation, 0, reversals.size());
            return new ReconciliationResult(ref, ReconOutcome.MISSING_IN_SWITCH, null, 0L, BigDecimal.ZERO, explanation);
        }

        SwitchRecord switchRecord = selectEarliestSwitch(switches);

        // Handle switch status == SUCCESS
        if (switchRecord.status() == SwitchStatus.SUCCESS) {
            // Additional Rule 3: If the switch says SUCCESS but a reversal exists,
            // report AMOUNT_MISMATCH with a note "reversal on a successful transaction".
            if (!reversals.isEmpty()) {
                ReversalRecord earliestReversal = selectEarliestReversal(reversals);
                String explanation = String.format(
                        "Switch reported SUCCESS, but reversal of %s on %s exists: reversal on a successful transaction.",
                        earliestReversal.amount(), earliestReversal.reversalDate());
                explanation = appendDuplicateNotes(explanation, switches.size(), reversals.size());
                return new ReconciliationResult(ref, ReconOutcome.AMOUNT_MISMATCH, null, 0L, BigDecimal.ZERO, explanation);
            }

            // Additional Rule 2: If the switch says SUCCESS and the ledger and switch amounts differ,
            // report AMOUNT_MISMATCH. If they match, OK.
            if (ledger.amount().compareTo(switchRecord.amount()) != 0) {
                String explanation = String.format(
                        "Switch reported SUCCESS, but ledger amount (%s) differs from switch amount (%s).",
                        ledger.amount(), switchRecord.amount());
                explanation = appendDuplicateNotes(explanation, switches.size(), 0);
                return new ReconciliationResult(ref, ReconOutcome.AMOUNT_MISMATCH, null, 0L, BigDecimal.ZERO, explanation);
            }

            // Rule 3: Successful switch record with matching ledger entry means OK
            String explanation = String.format(
                    "Transaction %s (%s) succeeded; ledger and switch amounts match (%s).",
                    ledger.txnId(), ledger.type(), ledger.amount());
            explanation = appendDuplicateNotes(explanation, switches.size(), 0);
            return new ReconciliationResult(ref, ReconOutcome.OK, null, 0L, BigDecimal.ZERO, explanation);
        }

        // Handle switch status == FAILED
        LocalDate dueDate = compensationCalculator.calculateDueDate(ledger.debitDate(), ledger.type());

        // Check when no reversal exists for a failed transaction
        if (reversals.isEmpty()) {
            if (ledger.amount().compareTo(switchRecord.amount()) != 0) {
                String explanation = String.format(
                        "Failed transaction ledger amount (%s) differs from switch amount (%s).",
                        ledger.amount(), switchRecord.amount());
                explanation = appendDuplicateNotes(explanation, switches.size(), 0);
                return new ReconciliationResult(ref, ReconOutcome.AMOUNT_MISMATCH, dueDate, 0L, BigDecimal.ZERO, explanation);
            }

            // Rule 4: Failed transaction without a reversal and before or on its due date means PENDING_WITHIN_TAT
            if (!asOfDate.isAfter(dueDate)) {
                String explanation = String.format(
                        "Failed %s debit on %s; awaiting reversal within TAT (due %s, as-of %s).",
                        ledger.type(), ledger.debitDate(), dueDate, asOfDate);
                explanation = appendDuplicateNotes(explanation, switches.size(), 0);
                return new ReconciliationResult(ref, ReconOutcome.PENDING_WITHIN_TAT, dueDate, 0L, BigDecimal.ZERO, explanation);
            }

            // Rule 5: Failed transaction without a reversal after its due date means BREACH_OPEN
            long daysLate = compensationCalculator.calculateDaysLate(dueDate, asOfDate);
            BigDecimal compensation = compensationCalculator.calculateCompensation(daysLate);
            String explanation = String.format(
                    "Failed %s debit on %s remains unreversed past due date %s (%d days overdue as of %s).",
                    ledger.type(), ledger.debitDate(), dueDate, daysLate, asOfDate);
            explanation = appendDuplicateNotes(explanation, switches.size(), 0);
            return new ReconciliationResult(ref, ReconOutcome.BREACH_OPEN, dueDate, daysLate, compensation, explanation);
        }

        // Additional Rule 4: If a reference has multiple reversals, use the earliest one
        // and add a note saying duplicates were ignored.
        ReversalRecord earliestReversal = selectEarliestReversal(reversals);

        // Rule 6: A failed transaction with a reversal amount different from the debit amount means AMOUNT_MISMATCH
        if (earliestReversal.amount().compareTo(ledger.amount()) != 0) {
            String explanation = String.format(
                    "Failed %s debit amount (%s) does not match reversal amount (%s).",
                    ledger.type(), ledger.amount(), earliestReversal.amount());
            explanation = appendDuplicateNotes(explanation, switches.size(), reversals.size());
            return new ReconciliationResult(ref, ReconOutcome.AMOUNT_MISMATCH, dueDate, 0L, BigDecimal.ZERO, explanation);
        }

        // Rule 7: A matching reversal on or before the due date means RESOLVED_ON_TIME
        if (!earliestReversal.reversalDate().isAfter(dueDate)) {
            String explanation = String.format(
                    "Failed %s debit on %s reversed on time on %s (due %s).",
                    ledger.type(), ledger.debitDate(), earliestReversal.reversalDate(), dueDate);
            explanation = appendDuplicateNotes(explanation, switches.size(), reversals.size());
            return new ReconciliationResult(ref, ReconOutcome.RESOLVED_ON_TIME, dueDate, 0L, BigDecimal.ZERO, explanation);
        }

        // Rule 8: A matching reversal after the due date means RESOLVED_LATE
        long daysLate = compensationCalculator.calculateDaysLate(dueDate, earliestReversal.reversalDate());
        BigDecimal compensation = compensationCalculator.calculateCompensation(daysLate);
        String explanation = String.format(
                "Failed %s debit on %s reversed late on %s (due %s, %d days late).",
                ledger.type(), ledger.debitDate(), earliestReversal.reversalDate(), dueDate, daysLate);
        explanation = appendDuplicateNotes(explanation, switches.size(), reversals.size());
        return new ReconciliationResult(ref, ReconOutcome.RESOLVED_LATE, dueDate, daysLate, compensation, explanation);
    }

    private SwitchRecord selectEarliestSwitch(List<SwitchRecord> switches) {
        return switches.stream()
                .min(Comparator.comparing(SwitchRecord::date))
                .orElseThrow();
    }

    private ReversalRecord selectEarliestReversal(List<ReversalRecord> reversals) {
        return reversals.stream()
                .min(Comparator.comparing(ReversalRecord::reversalDate))
                .orElseThrow();
    }

    private String appendDuplicateNotes(String baseExplanation, int switchCount, int reversalCount) {
        StringBuilder sb = new StringBuilder(baseExplanation);
        if (reversalCount > 1) {
            sb.append(" Note: multiple reversals found (")
              .append(reversalCount)
              .append("); earliest reversal used and duplicates were ignored.");
        }
        if (switchCount > 1) {
            sb.append(" Note: multiple switch records found (")
              .append(switchCount)
              .append("); earliest switch record used and duplicate switch entries were ignored.");
        }
        return sb.toString();
    }

    private Map<String, List<LedgerEntry>> groupByRefLedger(List<LedgerEntry> entries) {
        Map<String, List<LedgerEntry>> map = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            map.computeIfAbsent(entry.normalizedRrn(), k -> new ArrayList<>()).add(entry);
        }
        return map;
    }

    private Map<String, List<SwitchRecord>> groupByRefSwitch(List<SwitchRecord> records) {
        Map<String, List<SwitchRecord>> map = new LinkedHashMap<>();
        for (SwitchRecord record : records) {
            map.computeIfAbsent(record.normalizedRrn(), k -> new ArrayList<>()).add(record);
        }
        return map;
    }

    private Map<String, List<ReversalRecord>> groupByRefReversal(List<ReversalRecord> records) {
        Map<String, List<ReversalRecord>> map = new LinkedHashMap<>();
        for (ReversalRecord record : records) {
            map.computeIfAbsent(record.normalizedRrn(), k -> new ArrayList<>()).add(record);
        }
        return map;
    }

    public CompensationCalculator getCompensationCalculator() {
        return compensationCalculator;
    }
}
