# OrderedMap (weight + payload)

HurriCache's signature container: key + weight + payload in one structure with a shared
TTL. It replaces the Redis "sorted set for ordering + hash for data" idiom — ordering
and content are updated in one operation and live under one TTL, without application
glue between two keys.

Use cases: routing candidates with weight and metadata, price lists, ratings with
attributes, delayed jobs with bodies.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createOrderedMap(key, map, ttl?)` → `KeyHintData` | Create with initial content (`OrderedPayload` = key+weight → `Variant`) | |
| `addElementOrderedMap(key, orderedKey, value)` → `Integer` | Add/update an element | |
| `getOrderedMap(key)` → `Map<OrderedPayload, Variant>` | Read the whole map | |
| `getElementInRangeOrderedMap(key, from, to)` → `Map<OrderedPayload, Variant>` | Weight range read | Top-N candidates, priority selection |
| `getAndDeleteElementInRangeOrderedMap(key, from, to)` → `Map<...>` | Atomically read and delete a range | Claiming ready jobs with their bodies |
| `getInContainer(key, orderedKey)` → `Variant` | Point read of a payload | |
| `removeFromContainerByKey(key, orderedKey)` → `Integer` | Remove an element | |
| `addInContainer` / other `*InContainer` | Atomics on fields | Counters inside elements |
| `getSize(key)` → `Integer` | Element count | |

## Notes

- `OrderedPayload` combines an element key and its weight — an element is addressed by
  the (key, weight) pair.
- One TTL per container: ordering and payload never drift apart in lifetime.
- Weight is uint64; reads are ordered by ascending weight.
