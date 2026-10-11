package com.txnrecon.report;

import com.txnrecon.io.CsvValidationError;
import com.txnrecon.model.ReconOutcome;
import com.txnrecon.model.ReconciliationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates human-readable console summary reports from reconciliation results and CSV validation errors.
 */
public class ReportGenerator {

    /**
     * Counts reconciliation results grouped by every {@link ReconOutcome}.
     * All enum values are initialized to 0 so the summary always shows all 10 outcomes.
     */
    public Map<ReconOutcome, Long> countByOutcome(List<ReconciliationResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        Map<ReconOutcome, Long> counts = new EnumMap<>(ReconOutcome.class);
        for (ReconOutcome outcome : ReconOutcome.values()) {
            counts.put(outcome, 0L);
        }
        for (ReconciliationResult r : results) {
            counts.put(r.outcome(), counts.get(r.outcome()) + 1L);
        }
        return counts;
    }

    /**
     * Filters for BREACH_OPEN outcomes and sorts them with the most overdue breaches first
     * (descending by daysLate, then ascending by normalizedRrn).
     */
    public List<ReconciliationResult> getOpenBreachesMostOverdueFirst(List<ReconciliationResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        return results.stream()
                .filter(r -> r.outcome() == ReconOutcome.BREACH_OPEN)
                .sorted(Comparator.comparingLong(ReconciliationResult::daysLate).reversed()
                        .thenComparing(ReconciliationResult::normalizedRrn))
                .toList();
    }

    /**
     * Calculates total illustrative compensation across all reconciliation results.
     */
    public BigDecimal calculateTotalCompensation(List<ReconciliationResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        BigDecimal total = BigDecimal.ZERO;
        for (ReconciliationResult r : results) {
            total = total.add(r.compensation());
        }
        return total;
    }

    /**
     * Formats the complete reconciliation report as a multi-line string ready for printing.
     */
    public String generateReport(
            List<ReconciliationResult> results,
            List<CsvValidationError> validationErrors,
            LocalDate asOfDate,
            BigDecimal dailyRate
    ) {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(validationErrors, "validationErrors must not be null");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        Objects.requireNonNull(dailyRate, "dailyRate must not be null");

        StringBuilder sb = new StringBuilder();
        String divider = "=".repeat(115);
        String thinDivider = "-".repeat(115);

        sb.append(divider).append(System.lineSeparator());
        sb.append("  TXNRECON - DIGITAL TRANSACTION RECONCILIATION ENGINE REPORT").append(System.lineSeparator());
        sb.append("  As-Of Date: ").append(asOfDate)
          .append("  |  Daily Compensation Rate: INR ").append(formatMoney(dailyRate))
          .append("/day").append(System.lineSeparator());
        sb.append("  NOTE: Simplified educational model. Not a certified implementation of RBI regulations.")
          .append(System.lineSeparator());
        sb.append(divider).append(System.lineSeparator()).append(System.lineSeparator());

        // 1. Most Overdue Open Breaches First
        List<ReconciliationResult> openBreaches = getOpenBreachesMostOverdueFirst(results);
        sb.append("1. OPEN TAT BREACHES (MOST OVERDUE FIRST) [Count: ").append(openBreaches.size()).append("]")
          .append(System.lineSeparator());
        sb.append(thinDivider).append(System.lineSeparator());
        if (openBreaches.isEmpty()) {
            sb.append("  None - no open TAT breaches as of ").append(asOfDate).append(".")
              .append(System.lineSeparator());
        } else {
            sb.append(String.format("  %-14s | %-18s | %-10s | %9s | %14s | %s%n",
                    "REFERENCE", "OUTCOME", "DUE DATE", "DAYS LATE", "COMPENSATION", "EXPLANATION"));
            sb.append("  ").append("-".repeat(113)).append(System.lineSeparator());
            for (ReconciliationResult b : openBreaches) {
                sb.append(formatResultRow(b));
            }
        }
        sb.append(System.lineSeparator());

        // 2. All Reconciled Transactions (with most overdue open breaches listed first)
        List<ReconciliationResult> orderedResults = results.stream()
                .sorted(Comparator
                        .comparingInt((ReconciliationResult r) -> r.outcome() == ReconOutcome.BREACH_OPEN ? 0 : 1)
                        .thenComparing(Comparator.comparingLong(ReconciliationResult::daysLate).reversed())
                )
                .toList();

        sb.append("2. ALL RECONCILED TRANSACTIONS [Total References: ").append(orderedResults.size()).append("]")
          .append(System.lineSeparator());
        sb.append(thinDivider).append(System.lineSeparator());
        sb.append(String.format("  %-14s | %-18s | %-10s | %9s | %14s | %s%n",
                "REFERENCE", "OUTCOME", "DUE DATE", "DAYS LATE", "COMPENSATION", "EXPLANATION"));
        sb.append("  ").append("-".repeat(113)).append(System.lineSeparator());
        for (ReconciliationResult r : orderedResults) {
            sb.append(formatResultRow(r));
        }
        sb.append(System.lineSeparator());

        // 3. Summary Counts Grouped by Outcome
        Map<ReconOutcome, Long> counts = countByOutcome(results);
        BigDecimal totalComp = calculateTotalCompensation(results);
        sb.append("3. SUMMARY COUNTS BY OUTCOME").append(System.lineSeparator());
        sb.append(thinDivider).append(System.lineSeparator());
        for (Map.Entry<ReconOutcome, Long> entry : counts.entrySet()) {
            sb.append(String.format("  %-22s : %d%n", entry.getKey().name(), entry.getValue()));
        }
        sb.append("  ").append("-".repeat(35)).append(System.lineSeparator());
        sb.append(String.format("  %-22s : %d%n", "TOTAL REFERENCES", results.size()));
        sb.append(String.format("  %-22s : INR %s%n", "TOTAL COMPENSATION", formatMoney(totalComp)));
        sb.append(System.lineSeparator());

        // 4. CSV Validation Errors
        sb.append("4. REJECTED CSV ROWS / VALIDATION ERRORS [Count: ").append(validationErrors.size()).append("]")
          .append(System.lineSeparator());
        sb.append(thinDivider).append(System.lineSeparator());
        if (validationErrors.isEmpty()) {
            sb.append("  None - all CSV rows passed validation.").append(System.lineSeparator());
        } else {
            for (CsvValidationError err : validationErrors) {
                sb.append(String.format("  [%s : line %d] %s | Raw row: \"%s\"%n",
                        err.sourceName(), err.lineNumber(), err.reason(), err.rawLine()));
            }
        }
        sb.append(divider).append(System.lineSeparator());

        return sb.toString();
    }

    private String formatResultRow(ReconciliationResult r) {
        String dueDateStr = r.dueDate() == null ? "-" : r.dueDate().toString();
        String compStr = "INR " + formatMoney(r.compensation());
        return String.format("  %-14s | %-18s | %-10s | %9d | %14s | %s%n",
                r.normalizedRrn(),
                r.outcome().name(),
                dueDateStr,
                r.daysLate(),
                compStr,
                r.explanation());
    }

    private String formatMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
