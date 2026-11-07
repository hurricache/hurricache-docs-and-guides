# Getting Started: HurriCache in 5 minutes

Path: start a standalone node → connect the Java client → first write, read and CAS.

## 1. Start a node

```bash
docker run -d --name hurricache-dev \
  -p 127.0.0.1:50000:50000 \
  alexaborisov/fastcache-standalone-noavx512:latest
```

The gRPC API listens on `127.0.0.1:50000`. No config needed — a default one is baked
into the image (1024 partitions, RESP connector on 6379, snapshots enabled).

## 2. Connect (Java)

```java
import com.hurricache.client.FastCacheAsyncStandaloneClient;
import com.hurricache.client.intf.Variant;
import java.time.Duration;

var cache = new FastCacheAsyncStandaloneClient(
        "127.0.0.1", 50000, 0, Duration.ofSeconds(5));
```

All calls return `CompletableFuture` — use `.get()` for simplicity or a future pipeline
in production.

## 3. First write and read

```java
// Strict creation: a second create on the same key is an error
cache.create("user:1:visits", Variant.ofLong(1)).get();

Variant v = cache.get("user:1:visits").get();
System.out.println(v.longValue()); // 1 — an int64, not a string
```

## 4. Atomics and CAS

```java
cache.add("user:1:visits", null, 1).get();          // 2

// Optimistic state transition — one RPC instead of WATCH/MULTI/EXEC
var res = cache.compareAndSet("user:1:visits", 2, 10).get();
System.out.println(res.getResult());                // true if the current value was 2
```

## 5. A container

```java
cache.createQueue("jobs", java.util.List.of(Variant.ofBytes("job-1".getBytes()))).get();
cache.pushTail("jobs", java.util.List.of(Variant.ofBytes("job-2".getBytes()))).get();

Variant job = cache.popHead("jobs").get(); // atomic: exactly one worker gets the task
```

## 6. TTL and persistence

```java
cache.setTtl("user:1:visits", 300_000).get(); // data TTL is in milliseconds
System.out.println(cache.getTtl("user:1:visits").get());
```

Snapshots are on by default in the standalone image: write a key, restart the container
(`docker restart hurricache-dev`), read the key back — it is still there.

## Next steps

- Data types and their APIs: [../datatypes](../datatypes/README.md)
- Redis command mapping: [../redis-mapping](../redis-mapping/README.md)
- A 3-container cluster: [../cluster-compose](../cluster-compose)
