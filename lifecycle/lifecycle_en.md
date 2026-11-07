# TTL and lifecycle

## Three independent time mechanisms

| Mechanism | Unit | What happens on expiry |
|---|---|---|
| Data TTL (`setTtl`) | milliseconds | the object becomes stale and is removed |
| Lock TTL (`lock`) | seconds | the lock is released automatically; data is untouched |
| Cluster stability window | seconds (30–120) | a short node restart without migrations |

## Data TTL

- Set at creation (`create*(..., ttlMs, ...)`) or later via `setTtl(key, ttlMs)`;
  read it with `getTtl(key)`.
- TTL applies to the whole object: a container has one TTL for all its elements.
- Removing TTL (persist): `setTtl` with the "never expires" value.
- A stale object reads as missing (`NOT_FOUND`); physical removal is done by the
  background sweeper and is invisible to clients.

## Lock TTL

- A lock has its own lifetime in seconds, unrelated to data TTL.
- An expired lock is released automatically: the object is unlocked, the data stays.
- So taking a lock "just in case" is safe — a stuck client can never lock data forever.

## Deletion and its deferral

- `remove` / `pop` / TTL expiry — logically immediate; physical reclamation is slightly
  deferred so that readers already holding a reference finish cleanly.
- This is transparent to clients: after `remove` the key is immediately gone.

## Rules of thumb

- Always set a TTL for session/temporary data — the cache takes care of itself.
- Do not use data TTL as a lock substitute or vice versa: they solve different problems.
