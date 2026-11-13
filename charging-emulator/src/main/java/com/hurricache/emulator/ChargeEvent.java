package com.hurricache.emulator;

/**
 * Charging event: a charge or a rollback of amount units on account.
 */
public record ChargeEvent(boolean rollback, String account, long amount) {

    /** Poison pill used to stop the workers. */
    public static final ChargeEvent POISON = new ChargeEvent(false, "", 0);
}
