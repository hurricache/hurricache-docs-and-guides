package com.hurricache.emulator;

/**
 * Charging engine: atomic conditional debit and refund.
 * Implementations: Postgres (conditional UPDATE) and HurriCache (CAS loop).
 */
public interface ChargingEngine extends AutoCloseable {

    /**
     * Debit amount from the balance of account, only if the funds are sufficient.
     * The operation is atomic: concurrent charges can never drive the balance negative.
     *
     * @return true if the amount was charged; false on insufficient funds, balance unchanged
     */
    boolean charge(String account, long amount) throws Exception;

    /** Refund amount to the balance of account (rollback of an unused quota). */
    void rollback(String account, long amount) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
