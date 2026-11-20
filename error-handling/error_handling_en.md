# Error handling

The Java client returns `CompletableFuture`; server errors arrive as
`ExecutionException` whose cause is `io.grpc.StatusRuntimeException`. The code is
`status.getCode()`.

## Error codes (from the server)

| gRPC code | When | What to do |
|---|---|---|
| `NOT_FOUND` | Key does not exist; container is empty for a pop | A normal cache miss, not a failure |
| `ALREADY_EXISTS` | `create` on an existing key | Use `update`/`replace`, or treat as the expected outcome of an existence probe |
| `INVALID_ARGUMENT` | Malformed request: unreadable key/type, bad parameters | Client bug — do not retry unchanged |
| `OUT_OF_RANGE` | Atomic overflow (int64) or index/range outside the container | Check bounds; overflow leaves the value unchanged |
| `PERMISSION_DENIED` | Object is locked by another owner | Wait/release; someone else's lock cannot be removed |
| `FAILED_PRECONDITION` | Cluster: the key does not live on this node. Trailing metadata `x-fastcache-route` carries the owner address | The smart client handles it; a standalone client should reconnect using the metadata address |
| `ABORTED` | Write rejected as a stale write: `stored_ts > client_ts` — the key already holds a newer version | Re-read the value and retry; protection, not a failure |
| `UNAVAILABLE` / `DEADLINE_EXCEEDED` | Network/node down, timeout | Retry with backoff; the smart client retries on the replica itself |

## Stale writes (`ABORTED`)

The server stamps every write with the time from the `x-hc-client-ts-bin` metadata
(client clock, GMT-0). If a request was delayed by the network and the key has since
been changed by a newer write (`stored_ts > client_ts`), the server rejects the stale one:

```
ABORTED: Write rejected: arrived too late. stored update ts=X, client send ts=Y
```

- The message carries both times (epoch ms): the stored version's ts and the client's send ts.
- This is an **honest rejection**, not a degradation: the alternative would be silently
  overwriting someone's newer data.
- Frequent `ABORTED` under load means either client/server clock skew (check NTP) or
  genuine concurrent writes to the same key from multiple clients.
- Old clients without `x-hc-client-ts-bin` get stamped by server arrival time — the stale
  check rarely fires for them, but precision is lower; the server logs a warning.

## "No key" ≠ "empty container"

- `get` on a missing key → `NOT_FOUND`.
- `getMap`/`getList` on an existing empty container → an empty collection, status OK.

Clients can distinguish "cache miss" from "no data".

## Pattern: catching correctly

```java
try {
    Variant v = cache.get("key").get();
} catch (ExecutionException e) {
    if (e.getCause() instanceof StatusRuntimeException sre) {
        switch (sre.getStatus().getCode()) {
            case NOT_FOUND -> { /* cache miss — normal */ }
            case PERMISSION_DENIED -> { /* locked by another owner */ }
            default -> throw sre;
        }
    }
}
```

## Retries

- `UNAVAILABLE`/`DEADLINE_EXCEEDED` — retry with exponential backoff; idempotent reads
  are always safe.
- Writes (`create`, `pop`, atomics) are not idempotent — retry only if you know the call
  never left the client (e.g. a failure before sending).
- The cluster smart client routes to the owner and retries on the replica transparently.
