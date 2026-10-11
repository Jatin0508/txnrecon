package com.txnrecon.engine;

import com.txnrecon.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReferenceNormalizer} and {@link CompensationCalculator}.
 */
class ReferenceNormalizerAndCalculatorTest {

    @Test
    void normalizesMixedCaseAndSpecialCharacters() {
        assertEquals("RRN1001A", ReferenceNormalizer.normalize(" rrn-1001/a "));
        assertEquals("UPI998877", ReferenceNormalizer.normalize("upi_99.88:77#"));
        assertEquals("1234567890", ReferenceNormalizer.normalize("123-456-7890"));
        assertEquals("", ReferenceNormalizer.normalize("---///   "));
        assertEquals("", ReferenceNormalizer.normalize(null));
    }

    @Test
    void calculatesDefaultDueDatesForAllTransactionTypes() {
        CompensationCalculator calculator = new CompensationCalculator();
        LocalDate debitDate = LocalDate.of(2026, 10, 1);

        // 1-day TAT channels
        assertEquals(LocalDate.of(2026, 10, 2), calculator.calculateDueDate(debitDate, TransactionType.UPI_P2P));
        assertEquals(LocalDate.of(2026, 10, 2), calculator.calculateDueDate(debitDate, TransactionType.IMPS));

        // 5-day TAT channels
        assertEquals(LocalDate.of(2026, 10, 6), calculator.calculateDueDate(debitDate, TransactionType.UPI_MERCHANT));
        assertEquals(LocalDate.of(2026, 10, 6), calculator.calculateDueDate(debitDate, TransactionType.ATM));
        assertEquals(LocalDate.of(2026, 10, 6), calculator.calculateDueDate(debitDate, TransactionType.CARD_POS));
    }

    @Test
    void daysLateIsNeverNegativeAndZeroOnDueDate() {
        CompensationCalculator calculator = new CompensationCalculator();
        LocalDate dueDate = LocalDate.of(2026, 10, 6);

        // Before due date -> 0
        assertEquals(0L, calculator.calculateDaysLate(dueDate, LocalDate.of(2026, 10, 4)));
        // On due date -> 0
        assertEquals(0L, calculator.calculateDaysLate(dueDate, LocalDate.of(2026, 10, 6)));
        // After due date -> exact calendar day difference
        assertEquals(1L, calculator.calculateDaysLate(dueDate, LocalDate.of(2026, 10, 7)));
        assertEquals(5L, calculator.calculateDaysLate(dueDate, LocalDate.of(2026, 10, 11)));
    }

    @Test
    void supportsConfigurableDailyRateAndConfigurableTat() {
        CompensationCalculator customCalculator = new CompensationCalculator(
                new BigDecimal("250.50"),
                Map.of(TransactionType.UPI_P2P, 2, TransactionType.ATM, 7)
        );

        LocalDate debitDate = LocalDate.of(2026, 10, 1);
        assertEquals(LocalDate.of(2026, 10, 3), customCalculator.calculateDueDate(debitDate, TransactionType.UPI_P2P));
        assertEquals(LocalDate.of(2026, 10, 8), customCalculator.calculateDueDate(debitDate, TransactionType.ATM));
        // Unspecified type falls back to default (IMPS = 1 day)
        assertEquals(LocalDate.of(2026, 10, 2), customCalculator.calculateDueDate(debitDate, TransactionType.IMPS));

        assertEquals(0, BigDecimal.ZERO.compareTo(customCalculator.calculateCompensation(0)));
        assertEquals(0, new BigDecimal("751.50").compareTo(customCalculator.calculateCompensation(3)));
    }

    @Test
    void rejectsNegativeCompensationRate() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompensationCalculator(new BigDecimal("-10")));
    }
}
