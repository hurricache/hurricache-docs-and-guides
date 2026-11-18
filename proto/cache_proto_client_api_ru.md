# HurriCache gRPC API — руководство по написанию клиента

Документация по контракту `grpc/proto/cache.proto` и его серверной реализации.
Источники: `grpc/proto/cache.proto`, `grpc/src/async_server.cxx`,
`grpc/src/async_server_raw.cxx`, `grpc/src/async_server_lists.cxx`,
`grpc/src/async_server_hashes.cxx`, `grpc/src/async_server_atomics.cxx`.

---

## 1. Общие сведения

- **Proto-пакет**: `hurricache`, синтаксис `proto3`.
- **Java-пакет**: `com.hurricache.grpc` (`java_multiple_files = true`, outer class `HurriCache`).
- В файле объявлено **два сервиса**:
  - `HurriCacheGrpcService` — публичное клиентское API. Реализовано в перечисленных `async_server*.cxx`.
  - `DataReplicationService` — внутренний сервис репликации между нодами кластера. Реализован в `async_replication_server.cxx`, **не предназначен для клиентов** (в этой документации описан только для полноты картины).
- Максимальный размер входящего сообщения на сервере — **64 МБ**.
- Соединение: insecure credentials (без TLS/аутентификации на уровне gRPC).

### Файлы реализации и зоны ответственности

| Файл | Функция регистрации | Что реализует |
|---|---|---|
| `async_server.cxx` | `RegisterRpcs` | Жизненный цикл сервера + `setTtl`, `getTtl`, `lockObject`, `unlockObject` |
| `async_server_raw.cxx` | `registerRaw` | Скалярные Key-Value: `createKeyValue`, `getValue`, `getAndDeleteValue`, `existKey`, `updateValue`, `remove`, `getSize` |
| `async_server_lists.cxx` | `registerLists` | Контейнеры: `createContainer`, `getContainer`, head/tail/позиционные чтения и удаления, вставки, диапазоны |
| `async_server_hashes.cxx` | `registerContainers` | Операции по ключу элемента внутри MAP/ORDERED_MAP/SET + `deleteFromContainerByKey` |
| `async_server_atomics.cxx` | `registerAtomics` | Атомики: `atomicExchange/Add/Sub/Or/And/Xor`, `atomicCompareAndSet` |

---

## 2. Базовые сообщения (строительные блоки)

### 2.1 Данные

| Сообщение | Поля | Назначение |
|---|---|---|
| `BinaryPayload` | `oneof data { bytes payload; int64 intPayload; double doublePayload }`, `uint32 size` | Тело значения. `payload` использует `ctype = CORD` (zero-copy). |
| `KeyBinaryPayload` | `bytes payload`, `uint32 size` | Тело ключа (сырые байты + длина). |
| `KeyHint` | `optional uint32 weak_hash`, `optional uint32 strong_hash` | Подсказки хэшей для быстрой маршрутизации/локальности в шардах. Сервер возвращает их в `ValueResponse.keyHint` / `KeyHintResponse` — клиент может кэшировать. |
| `CompressedInfo` | `bool enabled`, `optional uint32 rawSize` | Метаданные сжатия. |

### 2.2 Ключи и значения

| Сообщение | Поля | Назначение |
|---|---|---|
| `Key` | `KeyBinaryPayload payload`, `optional CompressedInfo compressionInfo`, `optional KeyHint keyHint`, `optional uint32 clientId` | Неупорядоченный ключ. **`clientId` берётся сервером именно отсюда** (`key.clientId`, не из верхнеуровневого поля запроса — см. раздел про локи). |
| `OrderedKey` | `payload`, `optional uint64 order`, `compressionInfo`, `keyHint`, `clientId` | Ключ с весом (`order`) для `ORDERED_MAP`/`ORDERED_SET`. |
| `ContainerKey` | `oneof key { Key container_key; uint64 pos }` | Адрес элемента внутри контейнера: по ключу (`container_key`) для MAP/ORDERED_MAP, по позиции (`pos`) для VECTOR/LIST. Используется контейнерными атомиками (`CreateRequest.container_key`). |
| `Value` | `BinaryPayload value`, `compressionInfo`, `optional uint64 ttl`, `optional LockInfo lock_info`, `optional ValueType value_type` | Скалярное/неупорядоченное значение. |
| `OrderedValue` | `BinaryPayload value`, `optional uint64 order`, `compressionInfo`, `ttl`, `lock_info`, `value_type` | Значение с весом для `ORDERED_SET`. |

Перечисления:

- `ValueType`: `AUTO=0, BYTES=1, INT=2, DOUBLE=3` — обязателен для атомик (операция выбирается по нему).
- `ContainerType`: `UNDEFINED=0, VECTOR=1, LIST=2, QUEUE=3, SET=4, MAP=5, ORDERED_MAP=6, ORDERED_SET=7`.
- `LockType`: `NO_LOCK=0, WRITE_LOCK=1, READ_LOCK=2, GLOBAL=3`.
- `LockStatus`: `OK=0, CANT_LOCK=1, CANT_UNLOCK=2, GENERIC_ERROR=3`.

`LockInfo`: `LockType type`, `optional uint32 lockedBy`, `optional uint64 lockedTill`.

---

## 3. Сервис `HurriCacheGrpcService` — полный мэппинг RPC

В таблице: RPC → запрос → ответ → файл реализации → поддерживаемые типы объектов.

### 3.1 Локи (`async_server.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `lockObject` | `LockRequest{key, lockType, optional clientId, optional lockDuration}` | `LockResponse{LockStatus result, optional message}` | Все типы |
| `unlockObject` | `UnLockRequest{key, optional clientId}` | `UnlockResponse{LockStatus result, optional message}` | Все типы |

### 3.2 TTL (`async_server.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `setTtl` | `TtlRequest{key, optional ttl}` | `BoolResponse` | Все типы |
| `getTtl` | `GetRequest{key}` | `TtlResponse{optional ttl}` | Все типы |

### 3.3 Скалярные Key-Value (`async_server_raw.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `createKeyValue` | `CreateRequest{key, optional container_key (ContainerKey), value}` | `KeyHintResponse{keyHint, optional relayed}` | Скаляры |
| `getValue` | `GetRequest{key}` | `ValueResponse{optional value_unordered, optional value_ordered, optional keyHint}` | Скаляры |
| `getAndDeleteValue` | `GetRequest` | `ValueResponse` | Скаляры |
| `existKey` | `GetRequest` | `BoolResponse` | Все типы |
| `updateValue` | `UpdateRequest{key, value}` | `UpdateValueResponse{bool result, optional value (старое)}` | Скаляры |
| `remove` | `GetRequest` | `BoolResponse` | Все типы |
| `getSize` | `GetRequest` | `IntResponse{uint32 size}` | Все типы |

### 3.4 Контейнеры: создание и стриминг (`async_server_lists.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `createContainer` | `CreateContainerRequest{key, type, optional ttl, optional lock_info, repeated key_unordered, repeated value_unordered, repeated key_ordered, repeated value_ordered}` | `KeyHintResponse` | VECTOR, LIST, QUEUE, SET, MAP, ORDERED_MAP, ORDERED_SET |
| `getContainer` | `GetRequest` | **stream** `BatchValueResponse` | Все контейнеры |
| `getSize` | `GetRequest` | `IntResponse` (число элементов) | Все контейнеры |

### 3.5 Границы и позиционные чтения (`async_server_lists.cxx`, `async_server_hashes.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `getHead` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getTail` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getValueInContainerByKey` | `ContainerGetRequest{key, element_key}` | `ValueResponse` | MAP, ORDERED_MAP |
| `getElementAtPosition` | `KeyPositionRequest{key, optional type, pos, optional end, reverse}` | `ValueResponse` | VECTOR, LIST (pos = индекс), ORDERED_SET (pos = вес) |
| `existInContainerByKey` | `ContainerGetRequest` | `BoolResponse` | MAP, ORDERED_MAP, SET, ORDERED_SET |

### 3.6 Pop-операции (чтение с удалением)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `getAndDeleteHead` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getAndDeleteTail` | `GetRequest` | `ValueResponse` | VECTOR, LIST, QUEUE |
| `getAndDeleteElementAtPosition` | `KeyPositionRequest` | `ValueResponse` | VECTOR, LIST, ORDERED_SET, ORDERED_MAP (pos = вес) |
| `getAndDeleteValueInContainer` | `ContainerGetRequest` | `ValueResponse` | MAP, ORDERED_MAP |
| `updateValueInContainer` | `UpdateContainerRequest{key, element_key, value}` | `UpdateValueResponse` (старое значение) | MAP, ORDERED_MAP |

### 3.7 Диапазоны

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `getElementsInRange` | `KeyPositionRequest{pos, end, reverse}` | **stream** `BatchValueResponse` | VECTOR, LIST (индексы), ORDERED_MAP, ORDERED_SET (веса) |
| `getAndDeleteElementsInRange` | `KeyPositionRequest{pos, end, reverse}` | **stream** `BatchValueResponse` (удалённые элементы) | VECTOR, LIST (индексы), ORDERED_SET, ORDERED_MAP (веса) |

### 3.8 Удаления

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `deleteHead` | `GetRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `deleteTail` | `GetRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `deleteElementAtPosition` | `KeyPositionRequest` (диапазон `[pos, end]`) | `IntResponse` | VECTOR, LIST (индексы), ORDERED_SET, ORDERED_MAP (веса) |
| `deleteFromContainerByKeyValue` | `RemoveFromContainerRequest{key, repeated values, repeated keys}` | `IntResponse` (число удалённых) | Все контейнеры |
| `deleteFromContainerByKey` | `ContainerGetRequest` | `IntResponse` (число удалённых) | MAP, ORDERED_MAP, SET, ORDERED_SET, VECTOR, LIST |

### 3.9 Вставки (`async_server_lists.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `addElementToTail` | `AddToRequest{key, optional type, optional ttl, optional pos, repeated key_unordered/value_unordered/key_ordered/value_ordered}` | `IntResponse` | VECTOR, LIST, QUEUE |
| `addElementToHead` | `AddToRequest` | `IntResponse` | VECTOR, LIST, QUEUE |
| `addElement` | `AddToRequest` | `IntResponse` (фактически вставлено) | Все контейнеры |
| `addElementToPositionByValue` | `AddToValRequest{key, optional ttl, isBefore, pos (Value-пивот), repeated value}` | `IntResponse` | VECTOR, LIST |

### 3.10 Атомики (`async_server_atomics.cxx`)

| RPC | Request | Response | Куда применимо |
|---|---|---|---|
| `atomicExchange` | `CreateRequest{key, optional container_key, value}` (value с `value_type`) | `ValueResponse` (**старое** значение) | Скаляры INT/DOUBLE; элементы контейнеров: VECTOR/LIST по `container_key.pos`, MAP/ORDERED_MAP по `container_key.container_key` |
| `atomicAdd` | `CreateRequest` | `ValueResponse` (старое) | то же |
| `atomicSub` | `CreateRequest` | `ValueResponse` (старое) | то же |
| `atomicOr` | `CreateRequest` | `ValueResponse` (старое) | Только INT (скаляры или элементы контейнеров) |
| `atomicAnd` | `CreateRequest` | `ValueResponse` (старое) | Только INT (скаляры или элементы контейнеров) |
| `atomicXor` | `CreateRequest` | `ValueResponse` (старое) | Только INT (скаляры или элементы контейнеров) |
| `atomicCompareAndSet` | `AtomicCas{key, expected, toSet}` | `AtomicCasRes{bool result, optional expected (актуальное при неудаче)}` | Скаляры INT/DOUBLE |

---

## 4. Семантика, важная для клиента

### 4.1 Ошибки и маршрутизация (кластер)

Сервер использует единый маппинг ошибок (`checkError`):

| Условие | gRPC-статус | Сообщение |
|---|---|---|
| Ключ не найден / неверный тип | `NOT_FOUND` | «Key not found or type is not correct» |
| Тип объекта не подходит операции | `INVALID_ARGUMENT` | «Incorrect type of object» |
| Объект заблокирован другим клиентом | `PERMISSION_DENIED` | «Access Denied by Lock» |
| Ключ живёт на другой ноде | `FAILED_PRECONDITION` | адрес(а) целевой ноды (`primary` или `primary,backup`) в `error_message` и в trailing metadata **`x-fastcache-route`** |
| Ключ уже существует (при create) | `ALREADY_EXISTS` | — |
| Нечитаемый ключ в запросе | `DATA_LOSS` | «Cant read key data protocol error» |

**Редирект**: при `FAILED_PRECONDITION` клиент должен прочитать `x-fastcache-route` из trailing metadata и повторить запрос на указанную ноду.

**Исключение**: `createKeyValue` и `createContainer` при неверной ноде возвращают статус `OK` с `KeyHintResponse.relayed = true` (запрос был переслан сервером самостоятельно) — адрес ноды в этом случае в status message.

### 4.2 Методы, кодирующие результат в payload (статус почти всегда OK)

- `existKey` — при отсутствии ключа статус `OK`, `value=false` (не `NOT_FOUND`!).
- `remove` — статус `OK`, результат в `value` (true, если ключ существовал).
- `lockObject` / `unlockObject` — статус `OK`, результат в `LockStatus result` (`CANT_LOCK`/`CANT_UNLOCK` + message при отказе; чужой лок — это `OK` + `CANT_LOCK`, а не `PERMISSION_DENIED`).
- `deleteFromContainerByKey` / `deleteFromContainerByKeyValue` — статус `OK`, результат в `IntResponse.size` (0 = ничего не удалено).
- `getHead`/`getTail`/`getAndDeleteHead`/`getAndDeleteTail` на пустом контейнере — `OK` с пустым значением (только `keyHint`).

### 4.3 TTL

- **TTL — абсолютный Unix-timestamp в миллисекундах** (не длительность). `setTtl` записывает значение как есть; `getTtl` возвращает этот же абсолютный timestamp (несмотря на комментарий «Remaining TTL» в proto).
- Если TTL не задан, поле `ttl` в `TtlResponse` **отсутствует** (не равно 0) — проверяйте `has_ttl()` / presence.
- `setTtl` без поля `ttl` → `INVALID_ARGUMENT` «No ttl provided».
- TTL можно задать при создании: `CreateRequest.value.ttl`, `CreateContainerRequest.ttl`, `AddToRequest.ttl`.

### 4.4 Локи

- `clientId` для лока берётся из **`Key.clientId`**, при этом `LockRequest.clientId` тоже должен быть задан (проверяется `has_clientid` на самом запросе) — заполняйте оба поля.
- Без `clientId` ставится **анонимный лок** (`lockedBy=0`), который может снять кто угодно.
- **Важное расхождение с proto-комментарием**: `lockDuration` трактуется сервером не как длительность в мс, а как **абсолютное время истечения в epoch-секундах**, записываемое напрямую в `lockedTill`. Если не задан — лок вечный (`UINT32_MAX`).
- Семантика доступа (`canAccess`): владелец имеет полный доступ, кроме `READ_LOCK` (при `READ_LOCK` писать не может никто, включая владельца); `WRITE_LOCK`/`GLOBAL` полностью блокируют остальных; истёкший лок снимается автоматически.
- Повторный `lockObject` владельцем разрешён (обновление лока).
- `unlockObject` идемпотентен: разблокировка незаблокированного/просроченного ключа — успех. Снять лок может владелец, SYSTEM_ADMIN или кто угодно при истёкшем/анонимном локе.
- Локи реплицируются (`LOCK_REQUEST`/`UNLOCK_REQUEST`) только при успехе.

### 4.5 Атомики

- Работают только со скалярными значениями типа `INT`/`DOUBLE` (поле `value.value_type` в запросе определяет операцию; BYTES → `INVALID_ARGUMENT`).
- `atomicExchange/Add/Sub/Or/And/Xor` возвращают **старое** значение (до операции).
- `INT`-операнд разрешён для `DOUBLE`-хранилища (обратное — нет: `DOUBLE`-операнд в `INT` → `INVALID_ARGUMENT`).
- `atomicOr/And/Xor` — только для `INT` (для `DOUBLE` → `INVALID_ARGUMENT`).
- `atomicAdd`/`atomicSub` проверяют переполнение: `OUT_OF_RANGE` без применения операции и без репликации.
- `atomicCompareAndSet`: `value_type` у `expected` и `toSet` обязаны совпадать. При неудаче `result=false`, а в поле `expected` ответа записывается **фактическое текущее** значение (семантика `compare_exchange_strong`). Реплицируется только успешный CAS.

#### Контейнерные атомики

- Если в `CreateRequest` присутствует `container_key` (`ContainerKey`), операция применяется к элементу внутри контейнера, а не к скалярному ключу:
  - `container_key.pos` — элемент по индексу в VECTOR/LIST.
  - `container_key.container_key` — элемент по ключу в MAP/ORDERED_MAP.
- Весь `CreateRequest` реплицируется на бэкапы (команды `ATOMIC_*`) — реплика применяет ту же операцию к тому же элементу.
- Ошибки: `NOT_FOUND` — нет контейнера или ключа элемента; `OUT_OF_RANGE` — индекс вне границ (и переполнение, как у скаляров); `INTERNAL` — тип контейнера не поддерживается (только VECTOR, LIST, MAP, ORDERED_MAP); `INVALID_ARGUMENT` — неверная форма адресации (`pos` для мап, ключ для VECTOR/LIST) или нечисловой элемент.

### 4.6 Стриминг (`getContainer`, `getElementsInRange`)

- Серверный стриминг: порции **`BatchValueResponse` до 64 элементов** в сообщении.
- Конец стрима — обычное завершение со статусом `OK` (пустого финального сообщения нет).
- Ошибки валидации (нет ключа, неверный тип) приходят как статус до начала потока.
- Формат батча по типам:
  - LIST / VECTOR / SET → только `value_unordered[]`.
  - ORDERED_SET → `value_ordered[]` (с весами в `order`).
  - MAP → параллельные массивы `key_unordered[]` + `value_unordered[]` (пары по индексу).
  - ORDERED_MAP → `key_ordered[]` + `value_unordered[]` (пары по индексу; в `getElementsInRange` значение идёт перед ключом, но пары всё равно сопоставляются по индексу).
- `getElementsInRange`: диапазон **`[pos, end]` включительный**; `end` по умолчанию = size-1; для ORDERED_* `pos`/`end` — веса (min/max); `reverse=true` — обратный обход. Пустой контейнер или несовместимый тип → `INTERNAL` (в отличие от `getContainer`, где пустой контейнер даёт просто пустой стрим).
- Reader-lock контейнера держится **весь стрим** — долгое чтение блокирует писателей; читайте стрим до конца и не бросайте его надолго.

### 4.6.1 `getAndDeleteElementsInRange` (стримовое удаление)

- Семантика диапазона как у `getElementsInRange`: `[pos, end]` включительно; по умолчанию `pos=0`, `end` = size-1 (VECTOR/LIST) или `UINT64_MAX` (ORDERED_*, веса).
- Границы **не зависят от направления**: при `pos > end` они меняются местами — `reverse` влияет только на **порядок выдачи**, удаляемое множество одинаково.
- Реализация: подходящие элементы под writer-lock переносятся во временный буфер, затем стримятся порциями по 64 уже без лока. Удаление атомарно, даже если клиент оборвёт стрим (элементы уже изъяты из контейнера).
- Пустой контейнер или диапазон без совпадений → пустой стрим со статусом `OK` (не ошибка).
- Репликация: весь диапазон отправляется на бэкап (`REMOVE_AT_POSITION` с материализованным `end`) сразу после локального извлечения — бэкап сойдётся даже при обрыве стрима.

### 4.7 Нюансы контейнерных операций

- `createContainer`: начальное наполнение — `value_unordered` (VECTOR/LIST/QUEUE/SET), `value_ordered` (ORDERED_SET), пары `key_unordered`+`value_unordered` (MAP), `key_ordered`+`value_unordered` (ORDERED_MAP). При разной длине массивов берётся min (не ошибка). Дубликаты в SET/MAP молча отбрасываются. `type=UNDEFINED` → `INVALID_ARGUMENT`.
- `addElement`: VECTOR/LIST — по `pos` (нет `pos` или `pos >= size` → вставка в конец); SET/ORDERED_SET — дубликаты пропускаются; MAP/ORDERED_MAP — пары ключ+значение, дубликаты ключей пропускаются. `IntResponse.size` = фактически вставлено.
- `addElementToTail/Head`: `size` = **запрошенное** число элементов (элементы, которые не удалось сконвертировать, молча пропускаются).
- `getElementAtPosition` / `getAndDeleteElementAtPosition`: для VECTOR/LIST `pos` — индекс (вне границ → `OUT_OF_RANGE`); для ORDERED_SET/ORDERED_MAP `pos` — **вес** (точное совпадение, иначе `NOT_FOUND`/`OUT_OF_RANGE`). `KeyPositionRequest.type`, если задан, проверяется на соответствие → `INVALID_ARGUMENT` «Type mismatch».
- `getAndDeleteElementAtPosition` для ORDERED_MAP удаляет **все** элементы с данным весом, возвращает первое значение.
- `deleteElementAtPosition` удаляет диапазон `[pos, end]` (по умолчанию `end = pos`); выход за границы — не ошибка, `OK` с `size=0`.
- `addElementToPositionByValue`: ищет первый элемент, равный `pos`-пивоту, вставляет до (`isBefore=true`) или после. Пивот не найден → `NOT_FOUND`, `size=0`.
- `deleteFromContainerByKeyValue`: VECTOR/LIST/SET/ORDERED_SET — удаление по совпадению **значений** (`values`); MAP/ORDERED_MAP — пара удаляется только при совпадении **и ключа, и значения** (`keys` + `values`).
- `getAndDeleteValueInContainer`: элемент не найден → `NOT_FOUND` (единообразно для MAP и ORDERED_MAP). Для ORDERED_MAP удаляются все записи с совпадающим ключом, возвращается первое значение.
- `updateValueInContainer` и `updateValue` возвращают **старое** значение в `UpdateValueResponse.value`.

---

## 5. Сервис `DataReplicationService` (внутренний, справочно)

Реализован в `grpc/src/async_replication_server.cxx` (не входит в клиентское API). Используется нодами кластера для репликации и WAL.

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
| `removeElementAtPosition` | `KeyPositionRequest` (одна позиция или диапазон `[pos, end]`) | `BoolResponse` |
| `removeFromContainerByKeyValue` | `RemoveFromContainerRequest` | `BoolResponse` |
| `removeInContainer` | `ContainerGetRequest` | `BoolResponse` |
| `addElementToTail` / `addElementToHead` / `addElement` | `AddToRequest` | `BoolResponse` |
| `addElementToPositionByValue` | `AddToValRequest` | `BoolResponse` |
| `atomicExchange/Add/Sub/Or/And/Xor` | `CreateRequest` | `BoolResponse` |
| `atomicCompareAndSet` | `AtomicCas` | `BoolResponse` |

`BatchCommand` — union-обёртка (все поля `optional`) над запросами: `LockRequest`, `UnLockRequest`, `TtlRequest`, `UpdateRequest`, `CreateRequest`, `GetRequest`, `CreateContainerRequest`, `KeyPositionRequest`, `KeyRangeRequest`, `AddToRequest`, `AddToValRequest`, `RemoveFromContainerRequest`, `ContainerGetRequest`, `UpdateContainerRequest`, `AtomicCas`. Тип команды задаётся полем `ReplicationCommandType` в `BatchSingleMessage` (значения см. в `cache.proto`, строки 310–338).

Отличие от публичного сервиса: все ответы — `BoolResponse`, ошибки маршрутизации/локов не применяются (ноды доверенные).

---

## 6. Шпаргалка: какие поля запроса критичны

| RPC | Обязательное / важное |
|---|---|
| Любой | `Key.payload` (байты ключа); `Key.clientId` — если нужны локи/идентификация |
| `createKeyValue` | `key`, `value` (payload + опционально `ttl`, `value_type`); обработать `relayed=true` |
| `createContainer` | `key`, `type` (не UNDEFINED), массивы начального наполнения по типу |
| `setTtl` | `ttl` обязателен, абсолютный epoch-мс |
| `lockObject` | `lockType`, `clientId` (и в `Key`, и в `LockRequest`); `lockDuration` = абсолютный epoch-с |
| `getElementAtPosition` и позиционные | `pos` = индекс (VECTOR/LIST) или вес (ORDERED_*) |
| `getElementsInRange` | `pos`, `end` (включительно), `reverse`; только VECTOR/LIST/ORDERED_MAP/ORDERED_SET |
| `addElement` | массивы значений/пар по типу контейнера; `pos` только для VECTOR/LIST |
| `addElementToPositionByValue` | `pos` — **Value**-пивот, `isBefore`, `value[]` |
| `deleteFromContainerByKeyValue` | `values[]` для всех; для MAP/ORDERED_MAP ещё и `keys[]` |
| `getAndDeleteElementsInRange` | `pos`, `end` (включительно, порядок границ не важен), `reverse`; пустое совпадение = пустой стрим |
| Атомики | `value.value_type` = INT/DOUBLE; CAS: `value_type` у `expected` и `toSet` одинаковый; контейнерные атомики: `container_key.pos` (VECTOR/LIST) или `container_key.container_key` (MAP/ORDERED_MAP) |

## 7. Рекомендации по написанию клиента

1. Генерируйте стабы из `cache.proto` штатным `protoc` + gRPC-плагином вашего языка (`package hurricache`).
2. Всегда проверяйте **и gRPC-статус, и payload** — часть ошибок закодирована в ответах (`BoolResponse.value`, `LockStatus`, `IntResponse.size`).
3. Реализуйте обработку редиректа: `FAILED_PRECONDITION` + metadata `x-fastcache-route` → повтор на целевую ноду; `relayed=true` → успех без повтора.
4. Кэшируйте `KeyHint` из ответов и подставляйте в последующие запросы по тому же ключу — ускоряет маршрутизацию.
5. Для стримов читайте до `OK`, собирая батчи по типу контейнера (см. 4.6); не забудьте про пары ключ/значение по индексу для MAP-типов.
6. Проверяйте presence `optional`-полей (`ttl` в `TtlResponse`, `value` в `ValueResponse` и т.д.), а не значения по умолчанию.
