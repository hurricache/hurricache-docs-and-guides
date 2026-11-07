# Locks (READ / WRITE / GLOBAL)

Locks are first-class API operations on any object (scalar or container), bound to the
owning client. They are available only through the typed gRPC API (the RESP connector
does not expose locks).

## Levels

| Level | Read | Write | Closest Redis pattern |
|---|---|---|---|
| `READ` | all clients read in parallel | owner only | no equivalent — GET does not lock |
| `WRITE` | owner only | owner only | SET NX EX, without a read/write distinction |
| `GLOBAL` | owner only | owner only | no client-side global locks in Redis |

## Java API

| Method | What it does |
|---|---|
| `lock(key, hint, level, lockTtlSeconds, clientId, timeout)` → `LockStatus` | Acquire a READ/WRITE/GLOBAL lock; `lockTtlSeconds` is the lock duration in seconds — the lock is released automatically when it expires |
| `unlock(key, hint, clientId, timeout)` → `LockStatus` | Release the lock (owner only) |

## Rules

- **Lock TTL and data TTL are different things.** A lock carries its own TTL **in
  seconds**, independent of the object's TTL. When the lock TTL expires the lock is
  **released automatically** — the object is unlocked, but the data is never deleted and
  stays available.
- **Only the owner controls the lock**: no other client can unlock it or change its
  parameters (unlike `DEL` on a SET NX EX key, which anyone can issue).
- **Atomic READ → WRITE upgrade**: the level upgrade is a single operation with no race
  window — nobody can slip in between release and re-acquire.
- Locks work for scalars and containers alike (a whole queue can be locked for the
  duration of a batch operation).

## When to use

- Critical sections over compound state (several container fields).
- Worker coordination: a READ lock for a consistent read, upgraded to WRITE for mutation.
- Protecting a queue/map for the duration of a batch operation.
