package com.hurricache.emulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic cache of the "subscriber -> master account" mapping. The topology is stored in
 * Postgres; on a cache miss we query the database and remember the result (including
 * "no parent"). One connection per thread (ThreadLocal), so a miss does not open
 * a new connection every time.
 */
public final class AccountDirectory {

    private final ConcurrentHashMap<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final EmulatorConfig cfg;
    private final ThreadLocal<PreparedStatement> lookupPs;

    public AccountDirectory(EmulatorConfig cfg) {
        this.cfg = cfg;
        this.lookupPs = ThreadLocal.withInitial(() -> {
            try {
                Connection conn = DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPass);
                return conn.prepareStatement("SELECT parent FROM accounts WHERE msisdn = ?");
            } catch (Exception e) {
                throw new RuntimeException("Failed to open DB connection for account directory", e);
            }
        });
    }

    /** Returns the account to charge: the master if there is one, otherwise the account itself. */
    public String chargingAccount(String msisdn) {
        Optional<String> parent = cache.get(msisdn);
        if (parent == null) {
            parent = loadParent(msisdn);
            cache.putIfAbsent(msisdn, parent);
        }
        return parent.orElse(msisdn);
    }

    private Optional<String> loadParent(String msisdn) {
        try {
            PreparedStatement ps = lookupPs.get();
            ps.setString(1, msisdn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load parent for " + msisdn, e);
        }
    }
}
