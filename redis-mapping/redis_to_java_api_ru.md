# Маппинг Redis-команд на Java-клиент HurriCache

HurriCache имеет два входа:

- **RESP-коннектор** (порт 6379) — совместимость: подключайте существующий Redis-клиент без изменений. Подходит для оценки, бенчмарков и постепенной миграции. В кластерном режиме нода, получившая команду не на свою партицию, сама ретранслирует её владельцу (relay) либо отвечает MOVED с адресом.
- **Типизированный gRPC API** — продакшн-путь: типизированные скаляры и контейнеры, локи, CAS, атомики, стриминг контейнеров. Кластерные клиенты сами изучают топологию и роутят ключи.

Этот документ отображает Redis-команды на методы Java-клиента
(`com.hurricache.client.intf.*`). Если у команды нет типизированного аналога —
она помечена как «только RESP».

Обозначения: `cache` — экземпляр `HurriCacheClientInterface`
(например `FastCacheAsyncStandaloneClient` или cluster-aware клиент).
Все методы асинхронные и возвращают `CompletableFuture`.

## Кластерный режим: relay и MOVED

Количество шардов задаётся конфигурацией (`partitions` в конфиге ноды, по умолчанию 1024).
Слот в ответе MOVED — это `hash(ключа) % partitions`.

В таблицах колонка «Кластер» означает:

- **relay** — при попадании не на свою партицию нода сама ретранслирует команду владельцу
  (прозрачно для клиента, +4–5 мс), либо отвечает `MOVED <slot> <ip:port>` с **точным
  адресом владельца данных**, если relay выключен;
- **local** — команда выполняется локально на ноде и никогда не релеится
  (серверные команды, KEYS/SCAN — обзор только своих master-партиций);
- **per-key** — мультиключевая команда ретранслируется поключево.

Локи и CAS через RESP намеренно недоступны — они есть только в типизированном gRPC API.

## Строки / скаляры

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `GET` | `get(key)` → `Variant` | relay | Типизированное чтение (int64 / double / bytes) |
| `SET` | `update(key, value, ...)` | relay | Upsert. В HurriCache create и update разделены |
| `SET NX` | `create(key, value, ...)` | relay | Строгое создание: существующий ключ = ошибка |
| `SET XX` | `replace(key, value, ...)` | relay | Только обновление существующего |
| `SET EX/PX` | `create(..., ttl, ...)` / `update(..., ttl, ...)` | relay | TTL — параметр вызова |
| `GETDEL` | `pop(key)` → `Variant` | relay | Атомарный get-and-delete одним запросом |
| `GETEX` | `get(key)` + `setTtl(key, ttl)` | relay | Двумя вызовами |
| `MGET` / `MSET` | несколько `get` / `create`/`update` | per-key | Пакетного аналога нет; конвейеризируйте futures |
| `INCR` / `INCRBY` | `add(key, hint, delta)` → `Number` | relay | Нативная int64-атомика |
| `DECR` / `DECRBY` | `sub(key, hint, delta)` | relay | |
| `INCRBYFLOAT` | `add(key, hint, doubleDelta)` | relay | double-атомика |
| `APPEND` / `SETRANGE` / `GETRANGE` / `STRLEN` | — | relay | Только RESP (сырые байтовые операции) |
| `SETNX` | `create(...)` | relay | Ошибка на существующем ключе = семантика NX |
| `SETEX` | `create(..., ttl, ...)` | relay | |

## Ключи и TTL

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `DEL` / `UNLINK` | `remove(key)` → `Boolean` | per-key | Удаляет скаляр или контейнер целиком |
| `EXISTS` | `containsKey(key)` → `Boolean` | per-key | Также `containsKey(key, containerKey)` для членов контейнера |
| `EXPIRE` / `PEXPIRE` / `EXPIREAT` | `setTtl(key, ttlMs)` → `Boolean` | relay | TTL данных — миллисекунды; TTL лока — секунды |
| `TTL` / `PTTL` | `getTtl(key)` → `Long` | relay | |
| `PERSIST` | `setTtl(key, UINT64_MAX)` | relay | |
| `TYPE` | тип доступен из `Variant` после `get` | relay | Отдельного метода нет |
| `KEYS` / `SCAN` | — | local | Только RESP; в кластере — обзор master-партиций этой ноды |
| `RENAME` / `RENAMENX` / `TOUCH` / `RANDOMKEY` | — | local | Только RESP |

## Хэши → HashMap-контейнер

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `HSET` / `HMSET` | `createMap(key, map, ttl)` или `putAll(key, map)` | relay | Мультипарный HSET при выключенном relay — MOVED с адресом владельца |
| `HGET` | `getInContainer(key, field)` → `Variant` | relay | |
| `HGETALL` | `getMap(key)` → `Map<Payload, Variant>` | relay | |
| `HDEL` | `removeFromContainerByKey(key, field)` | relay | |
| `HEXISTS` | `containsKey(key, field)` | relay | |
| `HLEN` | `getSize(key)` | relay | |
| `HKEYS` / `HVALS` | `getMap(key)` → `keySet()` / `values()` | relay | |
| `HINCRBY` / `HINCRBYFLOAT` | `addInContainer(key, field, delta)` | relay | Атомика по полю контейнера |
| `HSTRLEN` / `HRANDFIELD` / `HSCAN` | — | relay | Только RESP |

## Списки → LinkedList / ArrayList / FIFO queue

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `LPUSH` | `pushHead(key, values)` или `createList(...)` | relay | |
| `RPUSH` | `pushTail(key, values)` | relay | |
| `LPOP` | `popHead(key)` → `Variant` | relay | Атомарно |
| `RPOP` | `popTail(key)` → `Variant` | relay | Атомарно |
| `LLEN` | `getSize(key)` | relay | |
| `LRANGE` | `getRange(key, from, to)` / `getList(key)` | relay | |
| `LINDEX` | `getAt(key, pos)` | relay | |
| `LSET` | `exchangeInContainer(key, pos, value)` | relay | |
| `LINSERT` | `insertBefore` / `insertAfter` / `insertAt` | relay | |
| `LREM` | `removeAt(key, pos)` / `removeFromContainerByKeyAndValue(...)` | relay | |
| `LTRIM` | `getAndDeleteListElementsInRange(...)` | relay | Ближайший аналог; читает и удаляет диапазон |
| `LMOVE` / `RPOPLPUSH` | композиция `popTail` + `pushHead` | relay | В gRPC — не одна операция |
| `BLPOP` / `BRPOP` | — | relay | Только RESP (неблокирующая реализация) |
| `LPOS` | — | relay | Только RESP |

FIFO-очередь задач: `createQueue(...)` + `popHead` — атомарный pop для гонко-free назначения задач.

## Множества → HashSet-контейнер

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `SADD` | `createSet(key, members, ttl)` / `addElementUnordered(key, v)` | relay | |
| `SREM` | `removeFromContainerByKeyAndValue(key, member)` | relay | |
| `SMEMBERS` | `getSet(key)` → `List<Variant>` | relay | |
| `SISMEMBER` | `containsKey(key, member)` | relay | |
| `SCARD` | `getSize(key)` | relay | |
| `SPOP` | `popInContainer(key, member)` | relay | |
| `SRANDMEMBER` | `getSet` + выбор на клиенте | relay | |
| `SINTER` / `SUNION` / `SDIFF` | вычисляются на клиенте из `getSet` | local | В RESP выполняются на ноде без релея |
| `SMISMEMBER` / `SSCAN` | — | relay | Только RESP |

## Сортированные множества → OrderedSet / OrderedMap

В HurriCache вес и payload живут в одном контейнере (OrderedMap заменяет связку
sorted-set + hash в Redis).

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `ZADD` | `createOrderedSet(...)` / `addElementWithWeight(key, w, v)` | relay | Один ключ может иметь разные веса |
| `ZSCORE` | `getElementWithWeight(key, w)` → `Variant` | relay | Вес — часть адресации элемента |
| `ZRANGE` / `ZREVRANGE` | `getOrderedSet(key)` | relay | Порядок — по весу |
| `ZRANGEBYSCORE` | `getElementInRangeOrderedSet(key, from, to)` | relay | Диапазон по весу |
| `ZPOPMIN` / `ZPOPMAX` | `getAndDeleteElementInRangeOrderedSet(...)` | relay | Читает и удаляет диапазон |
| `ZREM` | `getAndRemoveElementWithWeight(key, w)` | relay | |
| `ZCARD` | `getSize(key)` | relay | |
| `ZINCRBY` | `incrementOrder(key, OrderedPayload(value, curWeight), delta)` | MOVED | Атомарный инкремент веса; в кластере — редирект на владельца (relay намеренно нет: нужен текущий вес, его вычитка = стрим всего сета) |
| — (нет команды) | `update(key, OrderedPayload(value, curWeight), newWeight)` | — | Абсолютная установка веса элемента — только gRPC API |
| `ZMSCORE` / `ZCOUNT` / `ZRANK` / `ZREVRANK` / `ZSCAN` | — | relay | Только RESP |

## Серверные команды — всегда local

`PING`, `ECHO`, `HELLO`, `INFO`, `CONFIG`, `COMMAND`, `CLIENT`, `MEMORY`, `SELECT`,
`FLUSHDB`, `FLUSHALL`, `QUIT`, `DBSIZE` — выполняются на той ноде, к которой подключён
клиент, и никогда не релеятся. `FLUSHDB` в кластере очищает только локальные партиции.

## Скрипты, транзакции, pub/sub

| Redis | Java API | Кластер | Примечание |
|---|---|---|---|
| `EVAL` / `EVALSHA` | не нужны | local | Атомики и CAS — нативные одно-RPC операции, Lua не требуется |
| `WATCH`/`MULTI`/`EXEC` (CAS-паттерн) | `compareAndSet(key, expected, new)` → `AtomicCasRes` | — | Оптимистичная запись одним вызовом; `getResult()` сообщает успех |
| `SUBSCRIBE` / `PUBLISH` | — | local | Pub/Sub в roadmap |

## То, чего в Redis нет

| Возможность | Java API |
|---|---|
| Локи READ / WRITE / GLOBAL | `lock(key, ...)` / `unlock(key, ...)` → `LockStatus` |
| CAS по значению | `compareAndSet(...)` → `AtomicCasRes` |
| Битовые атомики | `and` / `or` / `xor` (+ `*InContainer`) |
| Атомики внутри контейнеров | `addInContainer`, `exchangeInContainer`, `subInContainer`, `andInContainer`, `orInContainer`, `xorInContainer` |
| Взвешенные карты | `createOrderedMap`, `getOrderedMap`, `getElementInRangeOrderedMap`, `addElementOrderedMap` |
| Стриминг больших контейнеров | `getContainer` (стриминг батчами) |

## Relay-режим в кластере (RESP)

В кластерной сборке RESP-коннектор работает как прокси маршрутизации:

- Клиент может подключиться к **любой** ноде кластера и слать команды по любым ключам —
  топология кластера для Redis-клиента полностью прозрачна.
- Если нода не владеет партицией ключа, коннектор **сам ретранслирует** команду ноде-владельцу
  по внутреннему gRPC и возвращает ответ клиенту. Дополнительная задержка — порядка
  4–5 мс на операцию (см. таблицу производительности на сайте): это плата за прозрачность.
- Альтернатива — MOVED-режим: коннектор отвечает стандартным редиректом
  `MOVED <slot> <ip:port>`, где slot — `hash(key) % partitions` (количество шардов задаётся
  в конфигурации), а адрес — точное положение данных (IP и порт владельца). Кластерный
  Redis-клиент (например JedisCluster) переподключается сам.

### Placement ключей и отличия от Redis Cluster

- Слот определяется **строго по хэшу ключа** (`weak_hash % partitions`). Хэш — **не CRC16**,
  как в Redis Cluster, и количество слотов не фиксированные 16384, а `partitions` из конфига.
- **Хэш-теги Redis (`{tag}`) не поддерживаются**: через RESP-коннектор повлиять на placement
  ключа нельзя — ключ целиком идёт в хэш.
- В **нативном API** placement можно задать явно: `KeyHintData` позволяет передать свой
  weak hash — `KeyHintData.of(strongHash, weakHash)`. Ключи с одинаковым weak hash
  гарантированно попадают в одну партицию (и на одну ноду). Так делают локальность
  связанных ключей: один и тот же hint передаётся в любой вызов
  (`create`, `get`, `update`, ...).

Что выбрать:

| Сценарий | Режим |
|---|---|
| Обычный Redis-клиент без поддержки кластера (Jedis pool, redis-cli, redis-benchmark) | relay — клиент не замечает кластер |
| Кластерный Redis-клиент (JedisCluster и т.п.) | MOVED-редиректы, без лишнего хопа |
| Продакшн с требованиями к задержке | нативный gRPC smart-клиент: роутинг на владельца без релея |

## Важные отличия семантики

- **Строгая типизация**: скаляр — int64, double или byte array. Вызов не того типа
  отклоняется на границе API, а не парсится из строки.
- **Нет ленивого создания**: `create` только создаёт, `replace` только обновляет.
  Ошибка вместо тихой перезаписи.
- **Отсутствующее ≠ пустое**: чтение несуществующего ключа — `NOT_FOUND`,
  существующий пустой контейнер возвращает пустой результат.
