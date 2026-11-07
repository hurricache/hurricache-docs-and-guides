# Scalars (int64 / double / byte arrays)

Typed atomic values. The type is declared at creation and enforced at the API boundary:
a wrong-type call is an error, not a string-parsing attempt.

Use cases: counters, state flags, usage-based billing, rate limiting, small opaque
payloads (bytes).

## Lifecycle

| Java API | What it does | When to use |
|---|---|---|
| `create(key, value, ttl?)` → `KeyHintData` | Strict creation; an existing key is an error | State init; guarantees no silent overwrite |
| `get(key)` → `Variant` | Typed read | The response type matches the stored type |
| `replace(key, value, ...)` → `Variant` | Updates an existing value only | Keep update and create on separate paths |
| `update(key, value, ...)` → `Variant` | Upsert | Redis SET equivalent |
| `pop(key)` → `Variant` | Atomic get-and-delete in one request | Consume a computation result exactly once |
| `remove(key)` → `Boolean` | Delete | |

## Atomics (int64 / double)

Every operation is one RPC with hardware atomicity — no Lua.

| Java API | What it does | When to use |
|---|---|---|
| `add(key, hint, delta)` → `Number` | fetch-and-add; overflow → `OUT_OF_RANGE` | Counters, limits |
| `sub(key, hint, delta)` → `Number` | fetch-and-sub | |
| `exchange(key, hint, value)` → `Number` | Write new, return old | Atomic value swap |
| `and` / `or` / `xor` (key, hint, mask) → `Long` | Bitwise ops on int64, result returned immediately | Bit flags |
| `compareAndSet(key, expected, new)` → `AtomicCasRes` | Optimistic write "if X then Y"; `getResult()` reports success | Distributed state machines, consistent transitions without WATCH loops |

## Example: order state machine

```java
var cache = new FastCacheAsyncStandaloneClient("127.0.0.1", 50000, 0, Duration.ofSeconds(5));

long NEW = 0, PAID = 1;

cache.create("order:42:state", Variant.ofLong(NEW)).get();

AtomicCasRes res = cache.compareAndSet("order:42:state", NEW, PAID).get();
if (res.getResult()) {
    // the NEW -> PAID transition committed exactly once, cluster-wide
}
```
