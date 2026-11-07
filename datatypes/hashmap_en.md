# HashMap

A typed hash map: field (Payload) → value (Variant). Atomic operations on individual
fields run server-side. The analogue of a Redis hash, but with strictly typed fields
and values.

Use cases: user profiles, session settings, objects with fields, per-entity counters.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createMap(key, map, ttl?)` → `KeyHintData` | Create a map with initial content | Profile/settings initialization |
| `getMap(key)` → `Map<Payload, Variant>` | Read the whole map | Full object fetch |
| `getInContainer(key, field)` → `Variant` | Read a single field | Point reads |
| `putAll(key, map)` → `Integer` | Write/update a set of fields | Bulk updates |
| `update(key, field, value)` → `Variant` | Update a field | Point writes |
| `containsKey(key, field)` → `Boolean` | Field existence check | |
| `removeFromContainerByKey(key, field)` → `Integer` | Delete a field | |
| `popInContainer(key, field)` → `Variant` | Atomically read and delete a field | One-time tokens inside an object |
| `addInContainer(key, field, delta)` → `Number` | Atomic field increment (int64/double) | Per-entity counters without locks |
| `exchangeInContainer` / `subInContainer` / `andInContainer` / `orInContainer` / `xorInContainer` | Remaining field atomics | Bit flags and counters inside an object |
| `getSize(key)` → `Integer` | Number of fields | |

## Notes

- Fields are typed: a counter field stays int64, not a string.
- TTL applies to the whole container.
- Large maps can be streamed (batched) through the gRPC API.
