package com.hurricache.emulator;

import java.util.List;

/**
 * Configuration of the parallel charging emulator. Parses --key value arguments;
 * valueless flags: --provision.
 */
public final class EmulatorConfig {

    public enum Mode { POSTGRES, HURRICACHE }

    public Mode mode = Mode.POSTGRES;
    public int accounts = 100_000;
    public long initialBalance = 10_000L;
    public int parentsPercent = 20;
    public int threads = 16;
    public int durationSec = 60;
    public int queueSize = 10_000;
    public int chargeMax = 100;
    public double rollbackChance = 0.3;
    public boolean provision = false;
    public boolean keepData = false;

    public String dbUrl = "jdbc:postgresql://localhost:5432/benchmark";
    public String dbUser = "benchmark";
    public String dbPass = "benchmark";

    public List<String> seeds = List.of(
            "192.168.0.123:51000",
            "192.168.0.123:61000",
            "192.168.0.123:21000");

    public static EmulatorConfig parse(String[] args) {
        EmulatorConfig cfg = new EmulatorConfig();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if ("--provision".equals(key)) {
                cfg.provision = true;
                continue;
            }
            if ("--keep-data".equals(key)) {
                cfg.keepData = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            String value = args[++i];
            switch (key) {
                case "--mode" -> cfg.mode = Mode.valueOf(value.toUpperCase());
                case "--accounts" -> cfg.accounts = Integer.parseInt(value);
                case "--initial-balance" -> cfg.initialBalance = Long.parseLong(value);
                case "--parents-percent" -> cfg.parentsPercent = Integer.parseInt(value);
                case "--threads" -> cfg.threads = Integer.parseInt(value);
                case "--duration-sec" -> cfg.durationSec = Integer.parseInt(value);
                case "--queue-size" -> cfg.queueSize = Integer.parseInt(value);
                case "--charge-max" -> cfg.chargeMax = Integer.parseInt(value);
                case "--rollback-chance" -> cfg.rollbackChance = Double.parseDouble(value);
                case "--db-url" -> cfg.dbUrl = value;
                case "--db-user" -> cfg.dbUser = value;
                case "--db-pass" -> cfg.dbPass = value;
                case "--seeds" -> cfg.seeds = List.of(value.split(","));
                default -> throw new IllegalArgumentException("Unknown argument: " + key);
            }
        }
        return cfg;
    }

    @Override
    public String toString() {
        return "EmulatorConfig{mode=" + mode + ", accounts=" + accounts
                + ", initialBalance=" + initialBalance + ", parentsPercent=" + parentsPercent
                + ", threads=" + threads + ", durationSec=" + durationSec
                + ", queueSize=" + queueSize + ", chargeMax=" + chargeMax
                + ", rollbackChance=" + rollbackChance + ", provision=" + provision
                + ", seeds=" + seeds + '}';
    }
}
