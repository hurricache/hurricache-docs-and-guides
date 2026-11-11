# Redis command to HurriCache Java client mapping

HurriCache has two entry points:

- **RESP connector** (port 6379) — compatibility layer: point an existing Redis client at
  HurriCache without code changes. Meant for evaluation, benchmarking and gradual migration.
  In cluster mode, a node that receives a command for a partition it does not own relays it to
  the owner automatically (relay mode) or replies with a MOVED redirect.
- **Typed gRPC API** — the production path: typed scalars and containers, locks, CAS, atomics,
  container streaming. Cluster-aware clients learn the topology and route keys themselves.

This document maps Redis commands onto Java client methods
(`com.hurricache.client.intf.*`). Commands without a typed equivalent are marked
"RESP only".

Notation: `cache` is a `HurriCacheClientInterface` instance
(e.g. `FastCacheAsyncStandaloneClient` or a cluster-aware client).
All methods are asynchronous and return `CompletableFuture`.

## Cluster mode: relay and MOVED

The shard count is set in configuration (`partitions` in the node config, 1024 by default).
The slot in a MOVED reply is `hash(key) % partitions`.

In the tables below, the "Cluster" column means:

- **relay** — when the key belongs to another partition, the node relays the command to the
  owner itself (transparent to the client, +4–5 ms), or replies `MOVED <slot> <ip:port>` with
  the **exact address of the data owner** if relay is disabled;
- **local** — the command executes on the connected node and is never relayed
  (server commands; KEYS/SCAN cover only this node's master partitions);
- **per-key** — a multi-key command is relayed key by key.

Locks and CAS are intentionally not exposed through RESP — they exist only in the typed
gRPC API.

## Strings / scalars

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `GET` | `get(key)` → `Variant` | relay | Typed read (int64 / double / bytes) |
| `SET` | `update(key, value, ...)` | relay | Upsert. HurriCache separates create and update |
| `SET NX` | `create(key, value, ...)` | relay | Strict creation: an existing key is an error |
| `SET XX` | `replace(key, value, ...)` | relay | Updates an existing value only |
| `SET EX/PX` | `create(..., ttl, ...)` / `update(..., ttl, ...)` | relay | TTL is a call parameter |
| `GETDEL` | `pop(key)` → `Variant` | relay | Atomic get-and-delete in one request |
| `GETEX` | `get(key)` + `setTtl(key, ttl)` | relay | Two calls |
| `MGET` / `MSET` | multiple `get` / `create`/`update` | per-key | No batch counterpart; pipeline the futures |
| `INCR` / `INCRBY` | `add(key, hint, delta)` → `Number` | relay | Native int64 atomic |
| `DECR` / `DECRBY` | `sub(key, hint, delta)` | relay | |
| `INCRBYFLOAT` | `add(key, hint, doubleDelta)` | relay | double atomic |
| `APPEND` / `SETRANGE` / `GETRANGE` / `STRLEN` | — | relay | RESP only (raw byte operations) |
| `SETNX` | `create(...)` | relay | Error on an existing key matches NX semantics |
| `SETEX` | `create(..., ttl, ...)` | relay | |

## Keys and TTL

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `DEL` / `UNLINK` | `remove(key)` → `Boolean` | per-key | Deletes a scalar or an entire container |
| `EXISTS` | `containsKey(key)` → `Boolean` | per-key | Also `containsKey(key, containerKey)` for container members |
| `EXPIRE` / `PEXPIRE` / `EXPIREAT` | `setTtl(key, ttlMs)` → `Boolean` | relay | Data TTL is in milliseconds; lock TTL is in seconds |
| `TTL` / `PTTL` | `getTtl(key)` → `Long` | relay | |
| `PERSIST` | `setTtl(key, UINT64_MAX)` | relay | |
| `TYPE` | type is available from `Variant` after `get` | relay | No separate method |
| `KEYS` / `SCAN` | — | local | RESP only; in a cluster covers only this node's master partitions |
| `RENAME` / `RENAMENX` / `TOUCH` / `RANDOMKEY` | — | local | RESP only |

## Hashes → HashMap container

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `HSET` / `HMSET` | `createMap(key, map, ttl)` or `putAll(key, map)` | relay | Multi-field HSET with relay disabled answers MOVED with the owner address |
| `HGET` | `getInContainer(key, field)` → `Variant` | relay | |
| `HGETALL` | `getMap(key)` → `Map<Payload, Variant>` | relay | |
| `HDEL` | `removeFromContainerByKey(key, field)` | relay | |
| `HEXISTS` | `containsKey(key, field)` | relay | |
| `HLEN` | `getSize(key)` | relay | |
| `HKEYS` / `HVALS` | `getMap(key)` → `keySet()` / `values()` | relay | |
| `HINCRBY` / `HINCRBYFLOAT` | `addInContainer(key, field, delta)` | relay | Atomic on a container field |
| `HSTRLEN` / `HRANDFIELD` / `HSCAN` | — | relay | RESP only |

## Lists → LinkedList / ArrayList / FIFO queue

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `LPUSH` | `pushHead(key, values)` or `createList(...)` | relay | |
| `RPUSH` | `pushTail(key, values)` | relay | |
| `LPOP` | `popHead(key)` → `Variant` | relay | Atomic |
| `RPOP` | `popTail(key)` → `Variant` | relay | Atomic |
| `LLEN` | `getSize(key)` | relay | |
| `LRANGE` | `getRange(key, from, to)` / `getList(key)` | relay | |
| `LINDEX` | `getAt(key, pos)` | relay | |
| `LSET` | `exchangeInContainer(key, pos, value)` | relay | |
| `LINSERT` | `insertBefore` / `insertAfter` / `insertAt` | relay | |
| `LREM` | `removeAt(key, pos)` / `removeFromContainerByKeyAndValue(...)` | relay | |
| `LTRIM` | `getAndDeleteListElementsInRange(...)` | relay | Closest match; reads and deletes a range |
| `LMOVE` / `RPOPLPUSH` | composition of `popTail` + `pushHead` | relay | Not a single gRPC operation |
| `BLPOP` / `BRPOP` | — | relay | RESP only (non-blocking implementation) |
| `LPOS` | — | relay | RESP only |

Race-free task queue: `createQueue(...)` + `popHead` — an atomic pop for task assignment.

## Sets → HashSet container

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `SADD` | `createSet(key, members, ttl)` / `addElementUnordered(key, v)` | relay | |
| `SREM` | `removeFromContainerByKeyAndValue(key, member)` | relay | |
| `SMEMBERS` | `getSet(key)` → `List<Variant>` | relay | |
| `SISMEMBER` | `containsKey(key, member)` | relay | |
| `SCARD` | `getSize(key)` | relay | |
| `SPOP` | `popInContainer(key, member)` | relay | |
| `SRANDMEMBER` | `getSet` + client-side pick | relay | |
| `SINTER` / `SUNION` / `SDIFF` | computed client-side from `getSet` | local | Executed on the node, no relay |
| `SMISMEMBER` / `SSCAN` | — | relay | RESP only |

## Sorted sets → OrderedSet / OrderedMap

In HurriCache the weight and the payload live in one container (OrderedMap replaces
the Redis sorted-set-plus-hash pairing).

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `ZADD` | `createOrderedSet(...)` / `addElementWithWeight(key, w, v)` | relay | The same key may appear with different weights |
| `ZSCORE` | `getElementWithWeight(key, w)` → `Variant` | relay | Weight is part of element addressing |
| `ZRANGE` / `ZREVRANGE` | `getOrderedSet(key)` | relay | Ordered by weight |
| `ZRANGEBYSCORE` | `getElementInRangeOrderedSet(key, from, to)` | relay | Range by weight |
| `ZPOPMIN` / `ZPOPMAX` | `getAndDeleteElementInRangeOrderedSet(...)` | relay | Reads and deletes a range |
| `ZREM` | `getAndRemoveElementWithWeight(key, w)` | relay | |
| `ZCARD` | `getSize(key)` | relay | |
| `ZINCRBY` | `incrementOrder(key, OrderedPayload(value, curWeight), delta)` | MOVED | Atomic weight increment; in a cluster — redirect to the owner (deliberately no relay: it needs the current weight, and reading it means streaming the whole set) |
| — (no command) | `update(key, OrderedPayload(value, curWeight), newWeight)` | — | Absolute weight set for an element — gRPC API only |
| `ZMSCORE` / `ZCOUNT` / `ZRANK` / `ZREVRANK` / `ZSCAN` | — | relay | RESP only |

## Server commands — always local

`PING`, `ECHO`, `HELLO`, `INFO`, `CONFIG`, `COMMAND`, `CLIENT`, `MEMORY`, `SELECT`,
`FLUSHDB`, `FLUSHALL`, `QUIT`, `DBSIZE` execute on the node the client is connected to and
are never relayed. In a cluster, `FLUSHDB` clears only local partitions.

## Scripts, transactions, pub/sub

| Redis | Java API | Cluster | Notes |
|---|---|---|---|
| `EVAL` / `EVALSHA` | not needed | local | Atomics and CAS are native single-RPC operations; no Lua required |
| `WATCH`/`MULTI`/`EXEC` (CAS pattern) | `compareAndSet(key, expected, new)` → `AtomicCasRes` | — | Optimistic write in one call; `getResult()` reports success |
| `SUBSCRIBE` / `PUBLISH` | — | local | Pub/Sub is on the roadmap |

## What Redis does not have

| Capability | Java API |
|---|---|
| READ / WRITE / GLOBAL locks | `lock(key, ...)` / `unlock(key, ...)` → `LockStatus` |
| Compare-and-set | `compareAndSet(...)` → `AtomicCasRes` |
| Bitwise atomics | `and` / `or` / `xor` (+ `*InContainer`) |
| Atomics inside containers | `addInContainer`, `exchangeInContainer`, `subInContainer`, `andInContainer`, `orInContainer`, `xorInContainer` |
| Weighted maps | `createOrderedMap`, `getOrderedMap`, `getElementInRangeOrderedMap`, `addElementOrderedMap` |
| Streaming large containers | `getContainer` (batched streaming) |

## Relay mode in a cluster (RESP)

In a cluster build the RESP connector acts as a routing proxy:

- A client may connect to **any** cluster node and issue commands for any key — the cluster
  topology is fully transparent to a Redis client.
- If the node does not own the key's partition, the connector **relays the command itself**
  to the owning node over internal gRPC and returns the response to the client. The extra
  latency is on the order of 4–5 ms per operation (see the performance table on the website):
  that is the price of transparency.
- The alternative is MOVED mode: the connector replies with a standard
  `MOVED <slot> <ip:port>` redirect, where slot is `hash(key) % partitions` (the shard count
  is set in configuration) and the address is the exact location of the data (owner IP and
  port). A cluster-aware Redis client (e.g. JedisCluster) reconnects on its own.

### Key placement and differences from Redis Cluster

- The slot is derived **strictly from the key hash** (`weak_hash % partitions`). The hash is
  **not CRC16** as in Redis Cluster, and the slot count is not the fixed 16384 — it is
  `partitions` from the config.
- **Redis hash tags (`{tag}`) are not supported**: the RESP connector gives no way to
  influence key placement — the whole key goes into the hash.
- The **native API** allows explicit placement: `KeyHintData` carries a caller-supplied weak
  hash — `KeyHintData.of(strongHash, weakHash)`. Keys with the same weak hash are guaranteed
  to land in the same partition (and on the same node). This is how you co-locate related
  keys: pass the same hint to any call (`create`, `get`, `update`, ...).

Which to choose:

| Scenario | Mode |
|---|---|
| Plain Redis client without cluster support (Jedis pool, redis-cli, redis-benchmark) | relay — the client never notices the cluster |
| Cluster-aware Redis client (JedisCluster etc.) | MOVED redirects, no extra hop |
| Latency-sensitive production | native gRPC smart client: routes to the owner with no relay at all |

## Semantic differences worth knowing

- **Strict typing**: a scalar is int64, double or byte array. A wrong-type call is rejected
  at the API boundary instead of being parsed out of a string.
- **No lazy creation**: `create` only creates, `replace` only updates. An error instead of
  a silent overwrite.
- **Missing ≠ empty**: reading a non-existent key returns `NOT_FOUND`; an existing empty
  container returns an empty result.
