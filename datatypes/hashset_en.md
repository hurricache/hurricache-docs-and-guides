# HashSet

A classic set of unique typed values. Atomic member add/remove operations run
server-side.

Use cases: tags, unique identifiers, allow/deny lists, membership checks.

## Java API

| Method | What it does | When to use |
|---|---|---|
| `createSet(key, members, ttl?)` → `KeyHintData` | Create a set with initial content | |
| `addElementUnordered(key, value)` → `Integer` | Add a member | `SADD` analogue |
| `getSet(key)` → `List<Variant>` | Read the whole set | `SMEMBERS` analogue |
| `containsKey(key, member)` → `Boolean` | Membership check | `SISMEMBER` analogue |
| `removeFromContainerByKeyAndValue(key, member)` → `Integer` | Remove a member | `SREM` analogue |
| `popInContainer(key, member)` → `Variant` | Atomically take a member | `SPOP` analogue for a specific member |
| `getSize(key)` → `Integer` | Set cardinality | `SCARD` analogue |

## Notes

- Set algebra (`SINTER`/`SUNION`/`SDIFF`) is computed client-side from `getSet`.
- Duplicates are dropped — the set stores unique values.
- TTL applies to the whole container.
- If members need weights, see OrderedSet.
