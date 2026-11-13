package com.hurricache.emulator;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network event emulator: enqueues charge/rollback events for random accounts.
 * Invariant: the total refunded per account is always less than the total charged —
 * tracked via the outstanding map (charged minus refunded).
 */
public final class EventGenerator implements Runnable {

    private final EmulatorConfig cfg;
    private final BlockingQueue<ChargeEvent> queue;
    private final ConcurrentHashMap<String, AtomicLong> outstanding = new ConcurrentHashMap<>();
    private final long stopAtNanos;
    private volatile boolean stopped = false;

    public EventGenerator(EmulatorConfig cfg, BlockingQueue<ChargeEvent> queue, long durationNanos) {
        this.cfg = cfg;
        this.queue = queue;
        this.stopAtNanos = System.nanoTime() + durationNanos;
    }

    public void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try {
            while (!stopped && System.nanoTime() < stopAtNanos) {
                String account = String.valueOf(rnd.nextInt(1, cfg.accounts + 1));
                AtomicLong out = outstanding.computeIfAbsent(account, k -> new AtomicLong());
                long charged = out.get();
                ChargeEvent ev;
                if (rnd.nextDouble() < cfg.rollbackChance && charged > 1) {
                    // refund strictly less than charged
                    long amount = 1 + rnd.nextLong(charged - 1);
                    out.addAndGet(-amount);
                    ev = new ChargeEvent(true, account, amount);
                } else {
                    long amount = 1 + rnd.nextInt(cfg.chargeMax);
                    out.addAndGet(amount);
                    ev = new ChargeEvent(false, account, amount);
                }
                if (!offer(ev)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Offer with a timeout: do not block on a full queue once the run time is over. */
    private boolean offer(ChargeEvent ev) throws InterruptedException {
        while (!stopped && System.nanoTime() < stopAtNanos) {
            if (queue.offer(ev, 100, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }
}
