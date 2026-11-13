package com.hurricache.emulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Parallel charging emulator (OCS): event generator -> queue -> multi-threaded engine.
 * Two independent balance storage modes: Postgres and HurriCache.
 *
 * Run examples:
 *   provisioning + a run on Postgres:
 *     --mode postgres --provision --accounts 100000 --parents-percent 20 --threads 16 --duration-sec 60
 *   the same run on HurriCache:
 *     --mode hurricache --provision --accounts 100000 --parents-percent 20 --threads 16 --duration-sec 60
 */
public final class ChargingEmulator {

    private static final java.util.concurrent.atomic.AtomicInteger ERROR_LOG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void main(String[] args) throws Exception {
        EmulatorConfig cfg = EmulatorConfig.parse(args);
        System.out.println(cfg);

        if (cfg.provision) {
            provisionPostgres(cfg);
        }

        ChargingEngine engine = switch (cfg.mode) {
            case POSTGRES -> new PostgresChargingEngine(cfg);
            case HURRICACHE -> {
                HurriCacheChargingEngine h = new HurriCacheChargingEngine(cfg);
                if (cfg.provision) {
                    h.provision(cfg);
                }
                yield h;
            }
        };

        AccountDirectory directory = new AccountDirectory(cfg);
        BlockingQueue<ChargeEvent> queue = new ArrayBlockingQueue<>(cfg.queueSize);
        LatencyStats stats = new LatencyStats();

        EventGenerator generator = new EventGenerator(cfg, queue, TimeUnit.SECONDS.toNanos(cfg.durationSec));
        Thread generatorThread = new Thread(generator, "event-generator");

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads; i++) {
            Thread t = new Thread(() -> workerLoop(engine, directory, queue, stats), "charging-worker-" + i);
            workers.add(t);
        }

        long started = System.nanoTime();
        workers.forEach(Thread::start);
        generatorThread.start();

        generatorThread.join();
        for (int i = 0; i < cfg.threads; i++) {
            queue.put(ChargeEvent.POISON);
        }
        for (Thread t : workers) {
            t.join();
        }
        long wall = System.nanoTime() - started;

        stats.report(cfg.mode + " charging", wall);
        if (engine instanceof HurriCacheChargingEngine h && !cfg.keepData) {
            System.out.println("Cleaning up HurriCache balances...");
            long c0 = System.nanoTime();
            h.cleanup(cfg);
            System.out.printf("Cleanup done in %.1f s%n", (System.nanoTime() - c0) / 1e9);
        }
        engine.close();
    }

    /** Worker loop: resolve the master account through the cache, then one atomic balance operation. */
    private static void workerLoop(ChargingEngine engine, AccountDirectory directory,
                                   BlockingQueue<ChargeEvent> queue, LatencyStats stats) {
        LatencyStats.Recorder rec = stats.newRecorder();
        try {
            while (true) {
                ChargeEvent ev = queue.take();
                if (ev == ChargeEvent.POISON) {
                    return;
                }
                long t0 = System.nanoTime();
                try {
                    String target = directory.chargingAccount(ev.account());
                    if (ev.rollback()) {
                        engine.rollback(target, ev.amount());
                        rec.record(System.nanoTime() - t0, true, true);
                    } else {
                        boolean ok = engine.charge(target, ev.amount());
                        rec.record(System.nanoTime() - t0, false, ok);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    if (ERROR_LOG_COUNT.incrementAndGet() <= 5) {
                        System.out.println("worker error: " + e);
                        e.printStackTrace(System.out);
                    }
                    rec.error();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Postgres provisioning: the accounts(msisdn, balance, parent) table.
     * parentsPercent of the accounts are children with a random master
     * drawn from the accounts without a parent.
     */
    private static void provisionPostgres(EmulatorConfig cfg) throws Exception {
        System.out.println("Provisioning Postgres: " + cfg.accounts + " accounts, "
                + cfg.parentsPercent + "% with parents...");
        long t0 = System.nanoTime();

        List<Integer> ids = new ArrayList<>(cfg.accounts);
        for (int i = 1; i <= cfg.accounts; i++) {
            ids.add(i);
        }
        Collections.shuffle(ids);
        int childCount = cfg.accounts * cfg.parentsPercent / 100;
        List<Integer> masters = ids.subList(childCount, ids.size());

        try (Connection conn = DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPass);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS accounts");
            st.execute("CREATE TABLE accounts (msisdn VARCHAR(32) PRIMARY KEY, balance BIGINT NOT NULL,"
                    + " parent VARCHAR(32))");

            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO accounts (msisdn, balance, parent) VALUES (?, ?, ?)")) {
                for (int i = 0; i < ids.size(); i++) {
                    String msisdn = String.valueOf(ids.get(i));
                    ps.setString(1, msisdn);
                    ps.setLong(2, cfg.initialBalance);
                    if (i < childCount) {
                        String parent = String.valueOf(
                                masters.get(ThreadLocalRandom.current().nextInt(masters.size())));
                        ps.setString(3, parent);
                    } else {
                        ps.setNull(3, java.sql.Types.VARCHAR);
                    }
                    ps.addBatch();
                    if (i % 5000 == 4999) {
                        ps.executeBatch();
                        conn.commit();
                    }
                }
                ps.executeBatch();
                conn.commit();
            }
        }
        System.out.printf("Postgres provisioned in %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    private ChargingEmulator() {
    }
}
