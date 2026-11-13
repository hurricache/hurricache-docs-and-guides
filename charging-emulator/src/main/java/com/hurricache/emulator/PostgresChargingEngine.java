package com.hurricache.emulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Charging via Postgres. Conditional debit is a single atomic UPDATE with a
 * funds-sufficiency check; on a race for a hot row the contenders wait on the row lock.
 * One connection and one set of prepared statements per thread (JDBC is not thread-safe).
 */
public final class PostgresChargingEngine implements ChargingEngine {

    private static final String CHARGE_SQL =
            "UPDATE accounts SET balance = balance - ? WHERE msisdn = ? AND balance >= ?";
    private static final String ROLLBACK_SQL =
            "UPDATE accounts SET balance = balance + ? WHERE msisdn = ?";

    private final EmulatorConfig cfg;
    private final List<Connection> connections = new ArrayList<>();
    private final ThreadLocal<PreparedStatement> chargePs;
    private final ThreadLocal<PreparedStatement> rollbackPs;

    public PostgresChargingEngine(EmulatorConfig cfg) {
        this.cfg = cfg;
        ThreadLocal<Connection> connHolder = ThreadLocal.withInitial(this::openConnection);
        this.chargePs = ThreadLocal.withInitial(() -> prepare(connHolder.get(), CHARGE_SQL));
        this.rollbackPs = ThreadLocal.withInitial(() -> prepare(connHolder.get(), ROLLBACK_SQL));
    }

    private Connection openConnection() {
        try {
            Connection conn = DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPass);
            conn.setAutoCommit(true);
            synchronized (connections) {
                connections.add(conn);
            }
            return conn;
        } catch (Exception e) {
            throw new RuntimeException("Failed to open DB connection", e);
        }
    }

    private static PreparedStatement prepare(Connection conn, String sql) {
        try {
            return conn.prepareStatement(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean charge(String account, long amount) throws Exception {
        PreparedStatement ps = chargePs.get();
        ps.setLong(1, amount);
        ps.setString(2, account);
        ps.setLong(3, amount);
        return ps.executeUpdate() == 1;
    }

    @Override
    public void rollback(String account, long amount) throws Exception {
        PreparedStatement ps = rollbackPs.get();
        ps.setLong(1, amount);
        ps.setString(2, account);
        ps.executeUpdate();
    }

    @Override
    public void close() {
        synchronized (connections) {
            for (Connection conn : connections) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
