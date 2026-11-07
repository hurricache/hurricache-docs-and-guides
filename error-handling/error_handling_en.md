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
| `UNAVAILABLE` / `DEADLINE_EXCEEDED` | Network/node down, timeout | Retry with backoff; the smart client retries on the replica itself |

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
