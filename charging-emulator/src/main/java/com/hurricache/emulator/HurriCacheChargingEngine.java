package com.hurricache.emulator;

import com.hurricache.client.FastCacheAsyncSmartClient;
import com.hurricache.client.intf.HurriCacheClientInterface;
import com.hurricache.client.intf.Variant;
import com.hurricache.grpc.AtomicCasRes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Charging via HurriCache. The balance is a typed int64 scalar (key "balance:<msisdn>").
 * Conditional debit is a CAS loop "read -> check sufficiency -> compareAndSet"; a failed
 * compareAndSet already carries the current value, so a retry costs no extra read.
 * Refund is an atomic add.
 */
public final class HurriCacheChargingEngine implements ChargingEngine {

    private static final String KEY_PREFIX = "balance:";

    private final HurriCacheClientInterface cache;

    public HurriCacheChargingEngine(EmulatorConfig cfg) {
        this.cache = new FastCacheAsyncSmartClient(cfg.seeds, 0, Duration.ofSeconds(30));
    }

    @Override
    public boolean charge(String account, long amount) throws Exception {
        String key = KEY_PREFIX + account;
        Variant v = cache.get(key).get();
        if (v == null) {
            return false;
        }
        long balance = v.longValue();
        while (true) {
            if (balance < amount) {
                return false;
            }
            AtomicCasRes res = cache.compareAndSet(key, balance, balance - amount).get();
            if (res.getResult()) {
                return true;
            }
            if (!res.hasExpected()) {
                return false; // the key disappeared concurrently
            }
            // failed CAS returns the current value — retry without an extra get
            balance = res.getExpected().getValueUnordered().getValue().getIntPayload();
        }
    }

    @Override
    public void rollback(String account, long amount) throws Exception {
        cache.add(KEY_PREFIX + account, amount).get();
    }

    /** Balance provisioning: create, or exchange back to the initial value if the key already exists. */
    public void provision(EmulatorConfig cfg) throws Exception {
        List<CompletableFuture<?>> batch = new ArrayList<>();
        for (int i = 1; i <= cfg.accounts; i++) {
            String key = KEY_PREFIX + i;
            CompletableFuture<?> f = cache.create(key, Variant.ofLong(cfg.initialBalance))
                    .handle((hint, err) -> err == null)
                    .thenCompose(created -> created
                            ? CompletableFuture.completedFuture(null)
                            : cache.exchange(key, cfg.initialBalance));
            batch.add(f);
            if (batch.size() >= 1000) {
                CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).get();
                batch.clear();
            }
        }
        CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).get();
    }

    /** Deletes all balance keys from the cluster: N workers, each synchronously removing its own range. */
    public void cleanup(EmulatorConfig cfg) throws Exception {
        int threads = Math.max(1, cfg.threads);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int worker = t;
            workers[t] = new Thread(() -> {
                for (int i = worker + 1; i <= cfg.accounts; i += threads) {
                    try {
                        cache.remove(KEY_PREFIX + i).get();
                    } catch (Exception ignored) {
                    }
                }
            }, "cleanup-" + t);
            workers[t].start();
        }
        for (Thread w : workers) {
            w.join();
        }
    }

    @Override
    public void close() {
        cache.shutdown();
    }
}
