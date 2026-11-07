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

## Standalone vs cluster-aware

| | `FastCacheAsyncStandaloneClient` | Smart client |
|---|---|---|
| Topology | single node | routing table |
| Node failure | error to the caller | transparent retry on the replica |
| Interface | `HurriCacheClientInterface` — the same one | the same |

Moving from standalone to a cluster requires no application code changes: only the
client construction changes.
