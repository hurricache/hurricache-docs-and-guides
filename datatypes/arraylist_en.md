# ArrayList (vector)

A list with random access by index. Besides end operations it supports reading,
insertion, replacement and removal by position.

Use cases: ordered collections with positional access, ring buffers, index-based
state tables.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createVector(key, values, ttl?)` → `KeyHintData` | Create a vector with initial content | |
| `getVector(key)` → `List<Variant>` | Read the whole vector | |
| `getAt(key, pos)` → `Variant` | Read by index | `LINDEX` analogue |
| `getRange(key, from, to)` → `List<Variant>` | Read a range | `LRANGE` analogue |
| `insertAt(key, pos, value)` → `Integer` | Insert at position | |
| `insertBefore(key, pos, value)` / `insertAfter(key, pos, value)` → `Integer` | Insert before/after position | `LINSERT` analogue |
| `exchangeInContainer(key, pos, value)` → `Number` | Replace by index | `LSET` analogue |
| `removeAt(key, pos)` → `Integer` | Remove by position | |
| `popAt(key, pos)` → `Variant` | Atomically read and remove by position | |
| `getAndDeleteVectorElementsInRange(key, from, to)` → `List<Variant>` | Read and delete a range | |
| `pushHead` / `pushTail` / `popHead` / `popTail` | End operations | Same as LinkedList |
| `getSize(key)` → `Integer` | Length | |

## Notes

- Middle insert/remove is O(n); for heavy end work prefer LinkedList.
- TTL applies to the whole container.
