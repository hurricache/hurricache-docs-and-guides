# FIFO queue

A first-in-first-out queue with an atomic pop. The main scenario is distributing tasks
across concurrent workers: each element goes to exactly one client.

Use cases: order/task queues, work balancing across workers, event buffers.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createQueue(key, values, ttl?)` → `KeyHintData` | Create a queue with initial content | |
| `pushTail(key, values)` → `Integer` | Enqueue at the tail | Submitting a task |
| `popHead(key)` → `Variant` | Atomically take from the head | Assigning a task to a worker: one element — one recipient |
| `peekHead(key)` / `peekTail(key)` → `Variant` | Read without removing | Queue inspection |
| `deleteHead(count)` / `deleteTail(count)` → `Integer` | Delete N from an end | Cleanup/trimming |
| `getSize(key)` → `Integer` | Queue depth | Lag monitoring |

## Notes

- `popHead` atomicity eliminates races: no SET NX EX-style patterns needed to hand out
  tasks.
- For at-least-once delivery combine with a lock or application-level acknowledgement:
  pop removes the element immediately.
- TTL applies to the whole container.
