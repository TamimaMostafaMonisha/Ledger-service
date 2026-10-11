package dev.portfolio.ledger.common;

/** Money rules shared by accounts and transfers. M1 supports one currency. */
public final class Money {

    public static final String CURRENCY = "BDT";

    /** Two decimal places (poisha); matches numeric(19,2) in the database. */
    public static final int SCALE = 2;

    /** numeric(19,2) leaves 17 digits before the decimal point. */
    public static final int INTEGER_DIGITS = 17;

    private Money() {
    }
}
