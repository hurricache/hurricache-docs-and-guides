# Cluster-aware client

The cluster-aware (smart) client knows the cluster topology: it connects to any node,
fetches the routing table from the coordinator and then sends each key straight to the
partition owner — no relays.

## Read/write modes (`Mode`)

| Mode | Behavior |
|---|---|
| `MASTER` | Operations go to the partition master only |
| `MASTER_THAN_BACKUP` | Master first; replica if the master is unavailable |
| `BACKUP` | Replica only (offload reads from the master) |
| `LB_SMART` | Balancing between master and replica |

The mode can be switched on the fly: `client.setMode(Mode.BACKUP)` returns the client
for call chaining.

## Topology and routing

- The client obtains `RoutingInfo` (partition → node table) from the coordinator and
  refreshes it as the cluster changes.
- Key slot: `weak_hash % partitions` (the partition count comes from the cluster config).
- On master failure the client transparently retries on the replica; on topology changes
  the table refreshes automatically.

## Placement: KeyHintData

`KeyHintData.of(strongHash, weakHash)` lets you set a key's weak hash explicitly.
Keys with the same weak hash are guaranteed to land in the same partition (same node) —
that is how you co-locate related data (e.g. all keys of one order).

The hint is returned by `create*` and then passed to subsequent calls — the client
neither recomputes the hash nor touches the network an extra time.

### Explicit hints: commit fully or let the server decide

`KeyHintData` switches hashing from server-managed to client-managed —
for **both** hashes, and there is no hybrid mode:

- **strongHash — identity.** By default the engine computes it from the key
  itself. Supply your own if your keys are opaque identifiers (e.g. binary
  tokens) and the engine hash is not what you want to verify against.
- **weakHash — placement.** The partition is `weak_hash % partitions`.
  Same weak hash for related keys → same partition, same node
  (the ex-hash-tag pattern, done explicitly).

- **No hint (default):** the server derives both hashes from the key and
  keeps partitions balanced. Routing misses are resolved transparently
  (relay or FAILED_PRECONDITION with x-fastcache-route). NOT_FOUND always
  means exactly one thing: the key does not exist.
- **Explicit hint:** the server trusts what you passed completely and never
  re-routes or re-hashes. A strongHash mismatch or a wrong weak hash reads
  as NOT_FOUND — the node found nothing where you pointed it and will not
  look anywhere else.

NOT_FOUND under an explicit hint is therefore ambiguous: the key may be
missing — or your hashes may be wrong. Diagnostic: repeat the call without
the hint. If the key is found, the hint was wrong.

Rule of thumb: once you manage hashes for a set of keys, manage them to
the end — every read and write for those keys carries the same hint.
Mixing hinted and server-routed access to the same keys is an application
bug, not a server one.

> Redis: hash tags hidden inside key strings. HurriCache: an explicit typed
> placement parameter.

## Standalone vs cluster-aware

| | `FastCacheAsyncStandaloneClient` | Smart client |
|---|---|---|
| Topology | single node | routing table |
| Node failure | error to the caller | transparent retry on the replica |
| Interface | `HurriCacheClientInterface` — the same one | the same |

Moving from standalone to a cluster requires no application code changes: only the
client construction changes.
