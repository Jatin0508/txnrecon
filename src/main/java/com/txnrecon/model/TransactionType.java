package com.txnrecon.model;

/**
 * Supported digital payment channels and their default turnaround time (TAT) in calendar days.
 * Turnaround time is the maximum number of calendar days allowed after a failed debit
 * to auto-reverse funds back to the customer's account before compensation applies.
 */
public enum TransactionType {
    UPI_P2P(1),
    IMPS(1),
    UPI_MERCHANT(5),
    ATM(5),
    CARD_POS(5);

    private final int defaultTatDays;

    TransactionType(int defaultTatDays) {
        this.defaultTatDays = defaultTatDays;
    }

    public int getDefaultTatDays() {
        return defaultTatDays;
    }
}
