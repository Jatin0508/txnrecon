package com.txnrecon.engine;

import com.txnrecon.model.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates reversal due dates, late calendar days, and illustrative customer compensation.
 *
 * <p>Educational Disclaimer: This calculator implements a simplified educational model
 * (default ₹100 per calendar day past the turnaround time) and is NOT a certified implementation
 * of RBI Harmonisation of Turnaround Time (TAT) regulations.
 */
public class CompensationCalculator {

    public static final BigDecimal DEFAULT_DAILY_RATE = BigDecimal.valueOf(100);

    private final BigDecimal dailyRate;
    private final Map<TransactionType, Integer> tatDaysByType;

    /**
     * Creates a calculator with the default ₹100/day rate and default channel turnaround times.
     */
    public CompensationCalculator() {
        this(DEFAULT_DAILY_RATE, defaultTatMap());
    }

    /**
     * Creates a calculator with a custom daily compensation rate and default turnaround times.
     *
     * @param dailyRate Compensation amount per late calendar day (must be non-negative)
     */
    public CompensationCalculator(BigDecimal dailyRate) {
        this(dailyRate, defaultTatMap());
    }

    /**
     * Creates a calculator with both a custom daily rate and custom turnaround times per channel.
     *
     * @param dailyRate     Compensation amount per late calendar day (must be non-negative)
     * @param tatDaysByType Map of transaction types to turnaround time in calendar days
     */
    public CompensationCalculator(BigDecimal dailyRate, Map<TransactionType, Integer> tatDaysByType) {
        Objects.requireNonNull(dailyRate, "dailyRate must not be null");
        if (dailyRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dailyRate must not be negative");
        }
        Objects.requireNonNull(tatDaysByType, "tatDaysByType must not be null");

        this.dailyRate = dailyRate;
        this.tatDaysByType = new EnumMap<>(TransactionType.class);
        for (TransactionType type : TransactionType.values()) {
            int days = tatDaysByType.getOrDefault(type, type.getDefaultTatDays());
            if (days < 0) {
                throw new IllegalArgumentException("TAT days must not be negative for " + type);
            }
            this.tatDaysByType.put(type, days);
        }
    }

    private static Map<TransactionType, Integer> defaultTatMap() {
        Map<TransactionType, Integer> map = new EnumMap<>(TransactionType.class);
        for (TransactionType type : TransactionType.values()) {
            map.put(type, type.getDefaultTatDays());
        }
        return map;
    }

    /**
     * Returns the configured turnaround time (in calendar days) for the given transaction type.
     */
    public int getTatDays(TransactionType type) {
        Objects.requireNonNull(type, "TransactionType must not be null");
        return tatDaysByType.getOrDefault(type, type.getDefaultTatDays());
    }

    /**
     * Calculates the reversal due date: debit_date + configured TAT calendar days.
     */
    public LocalDate calculateDueDate(LocalDate debitDate, TransactionType type) {
        Objects.requireNonNull(debitDate, "debitDate must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return debitDate.plusDays(getTatDays(type));
    }

    /**
     * Calculates the number of calendar days late between the due date and a comparison date
     * (either the reversal date for RESOLVED_LATE or the as-of date for BREACH_OPEN).
     * Never returns a negative number: a date on or before the due date yields 0 days late.
     */
    public long calculateDaysLate(LocalDate dueDate, LocalDate comparisonDate) {
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        Objects.requireNonNull(comparisonDate, "comparisonDate must not be null");
        long diff = ChronoUnit.DAYS.between(dueDate, comparisonDate);
        return Math.max(0L, diff);
    }

    /**
     * Calculates illustrative compensation as dailyRate * daysLate using BigDecimal arithmetic.
     */
    public BigDecimal calculateCompensation(long daysLate) {
        if (daysLate <= 0) {
            return BigDecimal.ZERO;
        }
        return dailyRate.multiply(BigDecimal.valueOf(daysLate));
    }

    public BigDecimal getDailyRate() {
        return dailyRate;
    }

    public Map<TransactionType, Integer> getTatDaysByType() {
        return Map.copyOf(tatDaysByType);
    }
}
