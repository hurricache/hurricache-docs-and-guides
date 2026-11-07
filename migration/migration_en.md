# Migrating from Redis

A step-by-step path from a running Redis to HurriCache without stopping the service.

## Step 0. Check command coverage

Compare your command set against the coverage table:
[../redis-mapping](../redis-mapping/README.md). Strings, keys/TTL, hashes, lists, sets and
sorted sets are covered; pub/sub is on the roadmap.

## Step 1. Run HurriCache alongside (zero code changes)

```bash
docker run -d --name hurricache-dev \
  -p 127.0.0.1:50000:50000 -p 127.0.0.1:6379:6379 \
  alexaborisov/fastcache-standalone-noavx512:latest
```

The RESP connector listens on 6379 — point your existing Redis client at it
(same host/port, same protocol).

## Step 2. Take a baseline

```bash
redis-benchmark -p 6379 -t set,get -r 1000000 -n 1000000 -c 100 -d 512
```

Compare with your Redis. Expected numbers are in the performance table on the website.

## Step 3. Move hot paths to the gRPC API

Using the mapping table, replace operations with typed calls:

- `SET NX` / optimistic scenarios → `create` + `compareAndSet` (no WATCH loops, no Lua);
- counters → `add`/`sub`;
- hashes → `createMap`/`getMap`/`addInContainer`;
- sorted-set-plus-hash pairs → `OrderedMap` (weight and payload together, one TTL).

## Step 4. Cluster

- Plain Redis clients keep working through **relay**: any node accepts any command and
  relays it to the owner itself (+4–5 ms).
- Cluster-aware clients (JedisCluster) get `MOVED <slot> <ip:port>` with the exact owner
  address. Slot = `hash(key) % partitions`; the hash is not CRC16 and `{hash-tags}` are
  not supported.
- Production: the smart client — owner routing with no extra hops, transparent retry on
  the replica. Application code does not change when moving standalone → cluster.

## Step 5. Retire Redis

Once all traffic is on HurriCache (RESP or gRPC) and metrics are stable, Redis can be
taken out of service. HurriCache data lives in memory plus snapshots
(`storage_directory`).

## Common pitfalls

- **Numbers-as-strings**: Redis stores everything as strings; HurriCache is typed. After
  `INCR` the value is int64 — read it as a number, not a string.
- **create vs update**: `create` on an existing key is an error (`ALREADY_EXISTS`), not a
  silent overwrite. The upsert is `update`.
- **TTL**: in the native API TTL is in milliseconds (Redis EX is seconds) — do not mix
  them up.
