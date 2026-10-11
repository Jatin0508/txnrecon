package com.txnrecon.model;

/**
 * All possible classification outcomes produced by the reconciliation engine.
 */
public enum ReconOutcome {
    /** Switch reports SUCCESS, amounts match, and no unexpected reversal exists. */
    OK,

    /** Failed transaction whose matching reversal arrived on or before the TAT due date. */
    RESOLVED_ON_TIME,

    /** Failed transaction whose matching reversal arrived after the TAT due date (compensation applies). */
    RESOLVED_LATE,

    /** Failed transaction with no reversal yet, but the as-of date is still on or before the due date. */
    PENDING_WITHIN_TAT,

    /** Failed transaction with no reversal, and the as-of date is past the due date (compensation accrues). */
    BREACH_OPEN,

    /** Customer was debited in the bank ledger, but no matching record exists in the payment switch. */
    MISSING_IN_SWITCH,

    /** Payment switch reports a transaction, but no matching customer debit exists in the bank ledger. */
    MISSING_IN_LEDGER,

    /** More than one bank ledger debit exists for the same normalized transaction reference. */
    DUPLICATE_DEBIT,

    /** Discrepancy in transaction/reversal amounts, or a reversal occurred on a successful transaction. */
    AMOUNT_MISMATCH,

    /** Reversal record exists without a corresponding bank ledger debit entry. */
    ORPHAN_REVERSAL
}
