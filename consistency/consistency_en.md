# Consistency Guarantees

HurriCache uses a **per-partition single-owner** model. Each partition has exactly one
owner (master) and one replica (RF=2). "Active-Active" means there are no standby
nodes: every node serves traffic and masters some partitions. This is NOT multi-master
writes to the same key.

## What is guaranteed

| Property | Guarantee |
|---|---|
| Write acknowledgement | The client is acknowledged after the partition owner applies the operation |
| Replication | Changes propagate to the replica asynchronously, in ordered batches; lag is observable (`replication_queue_pending_commands{target}`) |
| Consistency | Writes to one key are serialized by the owner; conflicts resolve via versioning and client timestamps |
| Data loss window | If the owner fails before a change reaches the replica, that change can be lost — the window is bounded by the replication lag |
| Recovery | On owner failure the replica is promoted automatically after the stability window; RF=2 is restored by background migration |

## Versioning

Every key carries a `(version, timestamp)` pair:

- `version` — a monotonic per-key mutation counter;
- `timestamp` — mutation time. Source: the `x-hc-client-ts-bin` metadata (client clock,
  GMT-0), or server arrival time for old clients. Client and server clocks must be
  synchronized (NTP).

Apply rules on the replica:

- Scalar overwrites (`CREATE_VALUE`, `UPDATE_VALUE`, `SET_TTL`) — last-write-wins by
  timestamp: an outdated command is dropped.
- Other commands — dedup by version: a command with `stored_v >= cmd_v` is skipped.
- `ADD_TO_TAIL`, `ADD_TO_FRONT`, `ATOMIC_ADD`, `ATOMIC_SUB` — always applied
  (concurrent operations from two nodes are legitimate); replica metadata is pulled
  up to `max(stored, cmd)`.
- Locks and `REMOVE` — no version checks.

## Stale write: honest rejection of late writes

If a request was delayed by the network and the key has since been changed by a newer
write (`stored_ts > client_ts`), the server rejects it with `ABORTED`, including both
timestamps in the message. This protects newer data — it is not a failure: the client
decides whether to re-read and retry or to give up. See
[../error-handling](../error-handling).

## State restore

If a replica rejects a command (state divergence), the sender does not retry the delta —
it reads the current key value and sends a full snapshot (`restore=true`): the replica
recreates the value wholesale. Deltas do not accumulate; version divergence heals itself.

## Failure model

1. The coordinator detects a node failure (heartbeat).
2. The stability window passes (configurable, tens of seconds) — short restarts cause
   no false migrations.
3. The node is excluded, replicas of its partitions are promoted, routing is rebuilt;
   clients receive the new topology automatically.
4. Background migration restores RF=2 on the remaining nodes without stopping clients.
5. Snapshots are written to disk in the background (time/change-count triggers) and
   used on node restart. Automated snapshot-based cluster failover recovery is a
   roadmap item, not a shipped feature.

## What the model does NOT promise

- **Not strong consistency** in general: replica reads may lag by the replication lag.
  Owner reads are current.
- **Not zero data loss**: the loss window equals the asynchronous replication lag.
- Ties at equal timestamps (two writes from two nodes within the same millisecond) are
  resolved by the partition owner; no global cross-node order for one key is guaranteed
  in that case (mitigated by client timestamps + single ownership).
