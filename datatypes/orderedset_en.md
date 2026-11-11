# OrderedSet (weighted set)

A set where every element carries a weight (uint64). Elements are ordered by weight;
the same element may appear with different weights — something Redis does not have.
Server-side range reads by weight.

Use cases: ratings, priority queues, leaderboards, delayed jobs (weight = execution time).

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createOrderedSet(key, entries, ttl?)` → `KeyHintData` | Create with initial content | |
| `addElementWithWeight(key, weight, value)` → `Integer` | Add an element with a weight | `ZADD` analogue |
| `incrementOrder(key, OrderedPayload(value, curWeight), delta)` → `Boolean` | Atomic weight increment/decrement; leaving uint64 range → `OUT_OF_RANGE` | `ZINCRBY` over gRPC; element is addressed by exact (value+weight) |
| `update(key, OrderedPayload(value, curWeight), newWeight)` → `Boolean` | Set an absolute weight | Reordering an element |
| `getOrderedSet(key)` → `List<OrderedVariant>` | Read all, ordered by weight | `ZRANGE` analogue |
| `getElementInRangeOrderedSet(key, from, to)` → `List<OrderedVariant>` | Weight range read | `ZRANGEBYSCORE` analogue; "jobs due in the next hour" |
| `getAndDeleteElementInRangeOrderedSet(key, from, to)` → `List<OrderedVariant>` | Atomically read and delete a range | `ZPOPMIN/MAX` analogue; claiming due jobs |
| `getElementWithWeight(key, weight)` → `Variant` | Find an element by weight | Read by score |
| `getAndRemoveElementWithWeight(key, weight)` → `Variant` | Atomically take by weight | |
| `addInContainer(key, member, delta)` → `Number` | Atomic per-element increment | `ZINCRBY` analogue |
| `getSize(key)` → `Integer` | Element count | `ZCARD` analogue |

## Notes

- `OrderedVariant` carries both the value and the weight (`getOrder()`).
- Re-adding the same value with a different weight creates a separate element —
  duplicates by weight are allowed (unlike Redis, where the score is overwritten).
- TTL applies to the whole container.
- If members need a payload besides the weight, see OrderedMap.
