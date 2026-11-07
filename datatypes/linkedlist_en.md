# LinkedList

A doubly linked list of typed values. Efficient O(1) push/pop at both ends, atomic —
suitable for queues and stacks.

Use cases: task queues, stacks, event journals with double-ended access.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createList(key, values, ttl?)` → `KeyHintData` | Create a list with initial content | |
| `getList(key)` → `List<Variant>` | Read the whole list | Full fetch |
| `pushHead(key, values)` → `Integer` | Add at the head | Stack (LIFO) together with `popHead` |
| `pushTail(key, values)` → `Integer` | Add at the tail | Queue (FIFO) together with `popHead` |
| `popHead(key)` → `Variant` | Atomically take from the head | Race-free task assignment |
| `popTail(key)` → `Variant` | Atomically take from the tail | |
| `peekHead(key)` / `peekTail(key)` → `Variant` | Read without removing | |
| `deleteHead(count)` / `deleteTail(count)` → `Integer` | Delete N elements from an end | Journal trimming |
| `getAndDeleteListElementsInRange(key, from, to)` → `List<Variant>` | Read and delete a range | Batch consumption |
| `removeAt(key, pos)` → `Integer` | Remove by position | |
| `getSize(key)` → `Integer` | List length | |

## Notes

- All pop/push operations are atomic server-side — two clients can never take the same
  element.
- TTL applies to the whole container.
- If you need random access by index, see ArrayList.
