# HurriCache gRPC API — Client Developer Guide

Documentation of the `grpc/proto/cache.proto` contract and its server-side implementation.
Sources: `grpc/proto/cache.proto`, `grpc/src/async_server.cxx`,
`grpc/src/async_server_raw.cxx`, `grpc/src/async_server_lists.cxx`,
`grpc/src/async_server_hashes.cxx`, `grpc/src/async_server_atomics.cxx`.

---

## 1. Overview

- **Proto package**: `hurricache`, `proto3` syntax.
- **Java package**: `com.hurricache.grpc` (`java_multiple_files = true`, outer class `HurriCache`).
- The file declares **two services**:
  - `HurriCacheGrpcService` — the public client API. Implemented in the `async_server*.cxx` files listed above.
  - `DataReplicationService` — internal cluster replication service. Implemented in `async_replication_server.cxx`, **not intended for clients** (documented here for completeness only).
- Max incoming message size on the server: **64 MB**.
- Connection: insecure credentials (no TLS/authentication at the gRPC level).

### Implementation files and responsibilities

| File | Registration function | Implements |
|---|---|---|
| `async_server.cxx` | `RegisterRpcs` | Server lifecycle + `setTtl`, `getTtl`, `lockObject`, `unlockObject` |
| `async_server_raw.cxx` | `registerRaw` | Scalar key-value: `createKeyValue`, `getValue`, `getAndDeleteValue`, `existKey`, `updateValue`, `remove`, `getSize` |
| `async_server_lists.cxx` | `registerLists` | Containers: `createContainer`, `getContainer`, head/tail/positional reads and deletes, insertions, ranges |
| `async_server_hashes.cxx` | `registerContainers` | Per-element-key operations inside MAP/ORDERED_MAP/SET + `deleteFromContainerByKey` |
| `async_server_atomics.cxx` | `registerAtomics` | Atomics: `atomicExchange/Add/Sub/Or/And/Xor`, `atomicCompareAndSet` |

---

## 2. Core messages (building blocks)

### 2.1 Data payloads

| Message | Fields | Purpose |
|---|---|---|
| `BinaryPayload` | `oneof data { bytes payload; int64 intPayload; double doublePayload }`, `uint32 size` | Value body. `payload` uses `ctype = CORD` (zero-copy). |
| `KeyBinaryPayload` | `bytes payload`, `uint32 size` | Key body (raw bytes + length). |
| `KeyHint` | `optional uint32 weak_hash`, `optional uint32 strong_hash` | Hash hints for fast routing / shard locality. Returned by the server in `ValueResponse.keyHint` / `KeyHintResponse` — clients may cache them. |
| `CompressedInfo` | `bool enabled`, `optional uint32 rawSize` | Compression metadata. |

### 2.2 Keys and values

| Message | Fields | Purpose |
|---|---|---|
| `Key` | `KeyBinaryPayload payload`, `optional CompressedInfo compressionInfo`, `optional KeyHint keyHint`, `optional uint32 clientId` | Unordered key. **The server reads `clientId` from here** (`key.clientId`, not the top-level request field — see the locking section). |
| `OrderedKey` | `payload`, `optional uint64 order`, `compressionInfo`, `keyHint`, `clientId` | Key with a weight (`order`) for `ORDERED_MAP`/`ORDERED_SET`. |
| `ContainerKey` | `oneof key { Key container_key; uint64 pos }` | Element address inside a container: by key (`container_key`) for MAP/ORDERED_MAP, by position (`pos`) for VECTOR/LIST. Used by container atomics (`CreateRequest.container_key`). |
| `Value` | `BinaryPayload value`, `compressionInfo`, `optional uint64 ttl`, `optional LockInfo lock_info`, `optional ValueType value_type` | Scalar/unordered value. |
| `OrderedValue` | `BinaryPayload value`, `optional uint64 order`, `compressionInfo`, `ttl`, `lock_info`, `value_type` | Weighted value for `ORDERED_SET`. |

Enums:

- `ValueType`: `AUTO=0, BYTES=1, INT=2, DOUBLE=3` — required for atomics (selects the operation type).
- `ContainerType`: `UNDEFINED=0, VECTOR=1, LIST=2, QUEUE=3, SET=4, MAP=5, ORDERED_MAP=6, ORDERED_SET=7`.
- `LockType`: `NO_LOCK=0, WRITE_LOCK=1, READ_LOCK=2, GLOBAL=3`.
- `LockStatus`: `OK=0, CANT_LOCK=1, CANT_UNLOCK=2, GENERIC_ERROR=3`.

`LockInfo`: `LockType type`, `optional uint32 lockedBy`, `optional uint64 lockedTill`.

---

## 3. `HurriCacheGrpcService` — full RPC mapping

Table columns: RPC → request → response → implementation file → applicable object types.

### 3.1 Locking (`async_server.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `lockObject` | `LockRequest{key, lockType, optional clientId, optional lockDuration}` | `LockResponse{LockStatus result, optional message}` | All types |
| `unlockObject` | `UnLockRequest{key, optional clientId}` | `UnlockResponse{LockStatus result, optional message}` | All types |

### 3.2 TTL (`async_server.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `setTtl` | `TtlRequest{key, optional ttl}` | `BoolResponse` | All types |
| `getTtl` | `GetRequest{key}` | `TtlResponse{optional ttl}` | All types |

### 3.3 Scalar key-value (`async_server_raw.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `createKeyValue` | `CreateRequest{key, optional container_key (ContainerKey), value}` | `KeyHintResponse{keyHint, optional relayed}` | Scalars |
| `getValue` | `GetRequest{key}` | `ValueResponse{optional value_unordered, optional value_ordered, optional keyHint}` | Scalars |
| `getAndDeleteValue` | `GetRequest` | `ValueResponse` | Scalars |
| `existKey` | `GetRequest` | `BoolResponse` | All types |
| `updateValue` | `UpdateRequest{key, value}` | `UpdateValueResponse{bool result, optional value (old)}` | Scalars |
| `remove` | `GetRequest` | `BoolResponse` | All types |
| `getSize` | `GetRequest` | `IntResponse{uint32 size}` | All types |

### 3.4 Containers: creation and streaming (`async_server_lists.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `createContainer` | `CreateContainerRequest{key, type, optional ttl, optional lock_info, repeated key_unordered, repeated value_unordered, repeated key_ordered, repeated value_ordered}` | `KeyHintResponse` | VECTOR, LIST, QUEUE, SET, MAP, ORDERED_MAP, ORDERED_SET |
| `getContainer` | `GetRequest` | **stream** `BatchValueResponse` | All containers |
| `getSize` | `GetRequest` | `IntResponse` (element count) | All containers |

### 3.5 Boundary and positional reads (`async_server_lists.cxx`, `async_server_hashes.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `getHead` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getTail` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getValueInContainerByKey` | `ContainerGetRequest{key, element_key}` | `ValueResponse` | MAP, ORDERED_MAP |
| `getElementAtPosition` | `KeyPositionRequest{key, optional type, pos, optional end, reverse}` | `ValueResponse` | VECTOR, LIST (pos = index), ORDERED_SET (pos = weight) |
| `existInContainerByKey` | `ContainerGetRequest` | `BoolResponse` | MAP, ORDERED_MAP, SET, ORDERED_SET |

### 3.6 Pop operations (read-and-remove)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `getAndDeleteHead` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getAndDeleteTail` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getAndDeleteElementAtPosition` | `KeyPositionRequest` | `ValueResponse` | VECTOR, LIST, ORDERED_SET, ORDERED_MAP (pos = weight) |
| `getAndDeleteValueInContainer` | `ContainerGetRequest` | `ValueResponse` | MAP, ORDERED_MAP |
| `updateValueInContainer` | `UpdateContainerRequest{key, element_key, value}` | `UpdateValueResponse` (old value) | MAP, ORDERED_MAP |

### 3.7 Range reads

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `getElementsInRange` | `KeyPositionRequest{pos, end, reverse}` | **stream** `BatchValueResponse` | VECTOR, LIST (indexes), ORDERED_MAP, ORDERED_SET (weights) |
| `getAndDeleteElementsInRange` | `KeyPositionRequest{pos, end, reverse}` | **stream** `BatchValueResponse` (deleted elements) | VECTOR, LIST (indexes), ORDERED_SET, ORDERED_MAP (weights) |

### 3.8 Deletions

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `deleteHead` | `GetRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `deleteTail` | `GetRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `deleteElementAtPosition` | `KeyPositionRequest` (range `[pos, end]`) | `IntResponse` | VECTOR, LIST (indexes), ORDERED_SET, ORDERED_MAP (weights) |
| `deleteFromContainerByKeyValue` | `RemoveFromContainerRequest{key, repeated values, repeated keys}` | `IntResponse` (deleted count) | All containers |
| `deleteFromContainerByKey` | `ContainerGetRequest` | `IntResponse` (deleted count) | MAP, ORDERED_MAP, SET, ORDERED_SET, VECTOR, LIST |

### 3.9 Insertions (`async_server_lists.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `addElementToTail` | `AddToRequest{key, optional type, optional ttl, optional pos, repeated key_unordered/value_unordered/key_ordered/value_ordered}` | `IntResponse` | VECTOR, LIST, QUEUE |
| `addElementToHead` | `AddToRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `addElement` | `AddToRequest` | `IntResponse` (actually inserted) | All containers |
| `addElementToPositionByValue` | `AddToValRequest{key, optional ttl, isBefore, pos (Value pivot), repeated value}` | `IntResponse` | VECTOR, LIST |

### 3.10 Atomics (`async_server_atomics.cxx`)

| RPC | Request | Response | Applies to |
|---|---|---|---|
| `atomicExchange` | `CreateRequest{key, optional container_key, value}` (value with `value_type`) | `ValueResponse` (**old** value) | INT/DOUBLE scalars; container elements: VECTOR/LIST by `container_key.pos`, MAP/ORDERED_MAP by `container_key.container_key` |
| `atomicAdd` | `CreateRequest` | `ValueResponse` (old) | same |
| `atomicSub` | `CreateRequest` | `ValueResponse` (old) | same |
| `atomicOr` | `CreateRequest` | `ValueResponse` (old) | INT only (scalars or container elements) |
| `atomicAnd` | `CreateRequest` | `ValueResponse` (old) | INT only (scalars or container elements) |
| `atomicXor` | `CreateRequest` | `ValueResponse` (old) | INT only (scalars or container elements) |
| `atomicCompareAndSet` | `AtomicCas{key, expected, toSet}` | `AtomicCasRes{bool result, optional expected (actual value on failure)}` | INT/DOUBLE scalars |

---

## 4. Semantics that matter for clients

### 4.1 Errors and cluster routing

The server uses a single error mapping (`checkError`):

| Condition | gRPC status | Message |
|---|---|---|
| Key not found / wrong type | `NOT_FOUND` | "Key not found or type is not correct" |
| Object type doesn't fit the operation | `INVALID_ARGUMENT` | "Incorrect type of object" |
| Object locked by another client | `PERMISSION_DENIED` | "Access Denied by Lock" |
| Key lives on another node | `FAILED_PRECONDITION` | target node address(es) (`primary` or `primary,backup`) in `error_message` and in trailing metadata **`x-fastcache-route`** |
| Key already exists (on create) | `ALREADY_EXISTS` | — |
| Unreadable key in request | `DATA_LOSS` | "Cant read key data protocol error" |

**Redirect**: on `FAILED_PRECONDITION` the client must read `x-fastcache-route` from trailing metadata and retry the request against that node.

**Exception**: `createKeyValue` and `createContainer` on the wrong node return status `OK` with `KeyHintResponse.relayed = true` (the server forwarded the request itself) — the node address is in the status message.

### 4.2 Methods that encode the result in the payload (status almost always OK)

- `existKey` — missing key yields status `OK`, `value=false` (not `NOT_FOUND`!).
- `remove` — status `OK`, result in `value` (true if the key existed).
- `lockObject` / `unlockObject` — status `OK`, result in `LockStatus result` (`CANT_LOCK`/`CANT_UNLOCK` + message on refusal; a foreign lock is `OK` + `CANT_LOCK`, not `PERMISSION_DENIED`).
- `deleteFromContainerByKey` / `deleteFromContainerByKeyValue` — status `OK`, result in `IntResponse.size` (0 = nothing deleted).
- `getHead`/`getTail`/`getAndDeleteHead`/`getAndDeleteTail` on an empty container — `OK` with an empty value (only `keyHint` set).

### 4.3 TTL

- **TTL is an absolute Unix timestamp in milliseconds** (not a duration). `setTtl` stores the value as-is; `getTtl` returns the same absolute timestamp (despite the "Remaining TTL" comment in the proto).
- If no TTL is set, the `ttl` field in `TtlResponse` is **absent** (not 0) — check `has_ttl()` / presence.
- `setTtl` without the `ttl` field → `INVALID_ARGUMENT` "No ttl provided".
- TTL can be set at creation time: `CreateRequest.value.ttl`, `CreateContainerRequest.ttl`, `AddToRequest.ttl`.

### 4.4 Locking

- The lock owner id is taken from **`Key.clientId`**, and `LockRequest.clientId` must also be set (`has_clientid` is checked on the request itself) — fill in both fields.
- Without `clientId`, an **anonymous lock** (`lockedBy=0`) is created, which anyone can release.
- **Important divergence from the proto comment**: `lockDuration` is treated by the server not as a duration in ms but as an **absolute expiration time in epoch seconds**, written directly into `lockedTill`. If unset, the lock is permanent (`UINT32_MAX`).
- Access semantics (`canAccess`): the owner has full access except under `READ_LOCK` (with `READ_LOCK` nobody can write, including the owner); `WRITE_LOCK`/`GLOBAL` fully block everyone else; an expired lock is released automatically.
- A repeated `lockObject` by the owner is allowed (lock refresh).
- `unlockObject` is idempotent: unlocking an unlocked/expired key succeeds. A lock can be released by the owner, SYSTEM_ADMIN, or anyone if the lock is expired/anonymous.
- Locks are replicated (`LOCK_REQUEST`/`UNLOCK_REQUEST`) only on success.

### 4.5 Atomics

- Work only on scalar values of type `INT`/`DOUBLE` (`value.value_type` in the request selects the operation; BYTES → `INVALID_ARGUMENT`).
- `atomicExchange/Add/Sub/Or/And/Xor` return the **old** value (before the operation).
- An `INT` operand is allowed on `DOUBLE` storage (and vice versa is not: `DOUBLE` operand on `INT` storage → `INVALID_ARGUMENT`).
- `atomicOr/And/Xor` — `INT` only (`DOUBLE` → `INVALID_ARGUMENT`).
- `atomicAdd`/`atomicSub` check overflow: `OUT_OF_RANGE`, operation not applied, not replicated.
- `atomicCompareAndSet`: `value_type` of `expected` and `toSet` must match. On failure `result=false` and the response's `expected` field carries the **actual current** value (`compare_exchange_strong` semantics). Only a successful CAS is replicated.

#### Container atomics

- If `container_key` (a `ContainerKey`) is present in `CreateRequest`, the operation applies to an element inside a container instead of the top-level scalar:
  - `container_key.pos` — element at that index in VECTOR/LIST.
  - `container_key.container_key` — element with that key in MAP/ORDERED_MAP.
- The whole `CreateRequest` is replicated to backups (`ATOMIC_*` commands) — the replica applies the same operation to the same element.
- Error mapping for container atomics: `NOT_FOUND` — container or element key not found; `OUT_OF_RANGE` — index out of bounds (and overflow as for scalars); `INTERNAL` — container type not supported (only VECTOR, LIST, MAP, ORDERED_MAP); `INVALID_ARGUMENT` — wrong addressing form (`pos` for maps, key for VECTOR/LIST) or a non-numeric element.

### 4.6 Streaming (`getContainer`, `getElementsInRange`)

- Server streaming: **`BatchValueResponse` chunks of up to 64 elements** per message.
- End of stream — normal completion with status `OK` (no empty final message).
- Validation errors (missing key, wrong type) arrive as the status before the stream starts.
- Chunk layout by type:
  - LIST / VECTOR / SET → only `value_unordered[]`.
  - ORDERED_SET → `value_ordered[]` (weights in `order`).
  - MAP → parallel arrays `key_unordered[]` + `value_unordered[]` (paired by index).
  - ORDERED_MAP → `key_ordered[]` + `value_unordered[]` (paired by index; in `getElementsInRange` the value is appended before the key, but pairs still match by index).
- `getElementsInRange`: the range is **inclusive `[pos, end]`**; default `end` = size-1; for ORDERED_* `pos`/`end` are weights (min/max); `reverse=true` walks backwards. An empty container or incompatible type → `INTERNAL` (unlike `getContainer`, where an empty container simply yields an empty stream).
- The container's reader lock is held for the **entire stream** — a long read blocks writers; always drain the stream and never abandon it.

### 4.6.1 `getAndDeleteElementsInRange` (streamed delete)

- Range semantics mirror `getElementsInRange`: inclusive `[pos, end]`; defaults: `pos=0`, `end` = container size-1 (VECTOR/LIST) or `UINT64_MAX` (ORDERED_*, weights).
- Range boundaries are **order-agnostic**: if `pos > end` they are swapped — `reverse` only changes the **output order**, the deleted set is identical.
- Implementation: matching elements are moved to a temporary buffer under the container's writer lock, then streamed in 64-element chunks without holding the lock. The write itself is therefore atomic even if the client disconnects mid-stream (elements are already removed from the container).
- An empty container or a range that matches nothing → empty stream with `OK` (not an error).
- Replication: the full range is replicated (`REMOVE_AT_POSITION` with a materialized `end`) right after the local extraction, so the backup converges even if the stream aborts.

### 4.7 Container operation nuances

- `createContainer`: initial fill — `value_unordered` (VECTOR/LIST/QUEUE/SET), `value_ordered` (ORDERED_SET), pairs `key_unordered`+`value_unordered` (MAP), `key_ordered`+`value_unordered` (ORDERED_MAP). On mismatched array lengths the min is used (not an error). Duplicates in SET/MAP are silently dropped. `type=UNDEFINED` → `INVALID_ARGUMENT`.
- `addElement`: VECTOR/LIST — at `pos` (no `pos` or `pos >= size` → append at the end); SET/ORDERED_SET — duplicates are skipped; MAP/ORDERED_MAP — key+value pairs, duplicate keys are skipped. `IntResponse.size` = actually inserted.
- `addElementToTail/Head`: `size` = the **requested** element count (elements that fail conversion are silently skipped).
- `getElementAtPosition` / `getAndDeleteElementAtPosition`: for VECTOR/LIST `pos` is an index (out of bounds → `OUT_OF_RANGE`); for ORDERED_SET/ORDERED_MAP `pos` is a **weight** (exact match, otherwise `NOT_FOUND`/`OUT_OF_RANGE`). `KeyPositionRequest.type`, if set, is validated → `INVALID_ARGUMENT` "Type mismatch".
- `getAndDeleteElementAtPosition` on ORDERED_MAP removes **all** elements with the given weight and returns the first value.
- `deleteElementAtPosition` removes the range `[pos, end]` (default `end = pos`); out-of-bounds is not an error — `OK` with `size=0`.
- `addElementToPositionByValue`: finds the first element equal to the `pos` pivot, inserts before (`isBefore=true`) or after it. Pivot not found → `NOT_FOUND`, `size=0`.
- `deleteFromContainerByKeyValue`: VECTOR/LIST/SET/ORDERED_SET — removal by **value** match (`values`); MAP/ORDERED_MAP — a pair is removed only when **both key and value** match (`keys` + `values`).
- `getAndDeleteValueInContainer`: element not found → `NOT_FOUND` (uniform for MAP and ORDERED_MAP). For ORDERED_MAP all entries with a matching key are removed and the first value is returned.
- `updateValueInContainer` and `updateValue` return the **old** value in `UpdateValueResponse.value`.

---

## 5. `DataReplicationService` (internal, reference only)

Implemented in `grpc/src/async_replication_server.cxx` (not part of the client API). Used by cluster nodes for replication and the WAL.

| RPC | Request | Response |
|---|---|---|
| `batchMessage` | `BatchMessage{repeated BatchSingleMessage{ReplicationCommandType, BatchCommand}}` | `BoolResponse` |
| `lockObject` / `unlockObject` | `LockRequest` / `UnLockRequest` | `BoolResponse` |
| `setTtl` | `TtlRequest` | `BoolResponse` |
| `updateValue` | `UpdateRequest` | `BoolResponse` |
| `createKeyValue` | `CreateRequest` | `BoolResponse` |
| `remove` | `GetRequest` | `BoolResponse` |
| `createContainer` | `CreateContainerRequest` | `BoolResponse` |
| `updateValueInContainer` | `UpdateContainerRequest` | `BoolResponse` |
| `removeTail` / `removeHead` | `GetRequest` | `BoolResponse` |
| `removeElementAtPosition` | `KeyPositionRequest` (single position or range `[pos, end]`) | `BoolResponse` |
| `removeFromContainerByKeyValue` | `RemoveFromContainerRequest` | `BoolResponse` |
| `removeInContainer` | `ContainerGetRequest` | `BoolResponse` |
| `addElementToTail` / `addElementToHead` / `addElement` | `AddToRequest` | `BoolResponse` |
| `addElementToPositionByValue` | `AddToValRequest` | `BoolResponse` |
| `atomicExchange/Add/Sub/Or/And/Xor` | `CreateRequest` | `BoolResponse` |
| `atomicCompareAndSet` | `AtomicCas` | `BoolResponse` |

`BatchCommand` is a union wrapper (all fields `optional`) over the request types: `LockRequest`, `UnLockRequest`, `TtlRequest`, `UpdateRequest`, `CreateRequest`, `GetRequest`, `CreateContainerRequest`, `KeyPositionRequest`, `KeyRangeRequest`, `AddToRequest`, `AddToValRequest`, `RemoveFromContainerRequest`, `ContainerGetRequest`, `UpdateContainerRequest`, `AtomicCas`. The command kind is set by the `ReplicationCommandType` field in `BatchSingleMessage` (values in `cache.proto`, lines 310–338).

Difference from the public service: all responses are `BoolResponse`; routing/lock errors do not apply (nodes are trusted).

---

## 6. Cheat sheet: critical request fields

| RPC | Required / important |
|---|---|
| Any | `Key.payload` (key bytes); `Key.clientId` — if locks/identification are needed |
| `createKeyValue` | `key`, `value` (payload + optional `ttl`, `value_type`); handle `relayed=true` |
| `createContainer` | `key`, `type` (not UNDEFINED), initial-fill arrays matching the type |
| `setTtl` | `ttl` is required, absolute epoch-ms |
| `lockObject` | `lockType`, `clientId` (in both `Key` and `LockRequest`); `lockDuration` = absolute epoch-s |
| `getElementAtPosition` and positional ops | `pos` = index (VECTOR/LIST) or weight (ORDERED_*) |
| `getElementsInRange` | `pos`, `end` (inclusive), `reverse`; only VECTOR/LIST/ORDERED_MAP/ORDERED_SET |
| `addElement` | value/pair arrays matching the container type; `pos` only for VECTOR/LIST |
| `addElementToPositionByValue` | `pos` is a **Value** pivot, `isBefore`, `value[]` |
| `deleteFromContainerByKeyValue` | `values[]` for all types; plus `keys[]` for MAP/ORDERED_MAP |
| `getAndDeleteElementsInRange` | `pos`, `end` (inclusive, order-agnostic), `reverse`; empty match = empty stream |
| Atomics | `value.value_type` = INT/DOUBLE; CAS: `value_type` of `expected` and `toSet` must match; container atomics: `container_key.pos` (VECTOR/LIST) or `container_key.container_key` (MAP/ORDERED_MAP) |

## 7. Client implementation recommendations

1. Generate stubs from `cache.proto` with the standard `protoc` + gRPC plugin for your language (`package hurricache`).
2. Always check **both the gRPC status and the payload** — some errors are encoded in responses (`BoolResponse.value`, `LockStatus`, `IntResponse.size`).
3. Implement redirect handling: `FAILED_PRECONDITION` + `x-fastcache-route` metadata → retry on the target node; `relayed=true` → success without a retry.
4. Cache `KeyHint` values from responses and pass them back in subsequent requests for the same key — this speeds up routing.
5. For streams, read until `OK`, assembling chunks according to the container type (see 4.6); remember the index-paired key/value arrays for MAP types.
6. Check presence of `optional` fields (`ttl` in `TtlResponse`, `value` in `ValueResponse`, etc.) rather than relying on default values.
