# Client performance

## Async: pipeline the futures

Every call returns a `CompletableFuture`. A sequential `.get()` after each call makes
the client synchronous and loses 10–20× of throughput:

```java
// bad
for (var k : keys) cache.get(k).get();

// good
var futures = keys.stream().map(cache::get).toList();
for (var f : futures) f.get();
```

## Payload compression

- Values larger than the compression threshold (**1024 bytes** by default) are compressed
  on the client and decompressed transparently.
- The threshold is configurable at client construction. Small values are never
  compressed — no overhead.
- If your workload is heavy on 1–4 KB values, tune the threshold to your data.

## Routing hints

- Keep the `KeyHintData` returned by `create*` and pass it to subsequent calls — the
  client neither recomputes the hash nor re-fetches topology.
- In a cluster the hint is also explicit placement of related keys into one partition.

## Timeouts

- Every call takes a `timeout` (defaults to the client's setting). Under load, prefer a
  short read timeout plus a retry — it protects p99 better than long waiting.

## Large containers

- `getContainer` and bulk collection reads are streamed in batches — do not pull huge
  containers in the hot path; prefer ranges (`getElementInRangeOrderedSet` and friends).

## Server-side levers (briefly)

- CPU pinning of threads (node config) removes scheduler jitter.
- `HURRICACHE_WORKER_CORES=1,2,3` — pins the internal gRPC event-engine worker threads to
  the listed cores (separates the network stack from cache worker threads).
- Snapshots are background-only and never touch the hot path.
- Benchmarks and measurement methodology: the Performance tab on the website /
  `operations`.
