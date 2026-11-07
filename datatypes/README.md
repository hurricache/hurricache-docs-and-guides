# Типы данных HurriCache

По одному документу на тип контейнера. Русская и английская версии каждого файла.

| Тип | RU | EN |
|---|---|---|
| Скаляры (int64 / double / bytes) | [scalars_ru.md](scalars_ru.md) | [scalars_en.md](scalars_en.md) |
| HashMap | [hashmap_ru.md](hashmap_ru.md) | [hashmap_en.md](hashmap_en.md) |
| LinkedList | [linkedlist_ru.md](linkedlist_ru.md) | [linkedlist_en.md](linkedlist_en.md) |
| ArrayList (vector) | [arraylist_ru.md](arraylist_ru.md) | [arraylist_en.md](arraylist_en.md) |
| FIFO queue | [queue_ru.md](queue_ru.md) | [queue_en.md](queue_en.md) |
| HashSet | [hashset_ru.md](hashset_ru.md) | [hashset_en.md](hashset_en.md) |
| OrderedSet (взвешенное множество) | [orderedset_ru.md](orderedset_ru.md) | [orderedset_en.md](orderedset_en.md) |
| OrderedMap (вес + payload) | [orderedmap_ru.md](orderedmap_ru.md) | [orderedmap_en.md](orderedmap_en.md) |
| Локи (READ / WRITE / GLOBAL) | [locks_ru.md](locks_ru.md) | [locks_en.md](locks_en.md) |

## Общие операции (для всех типов)

| Java API | Что делает |
|---|---|
| `getSize(key)` → `Integer` | Размер: количество элементов контейнера |
| `setTtl(key, ttlMs)` → `Boolean` | Установить/обновить TTL объекта (миллисекунды; TTL лока — в секундах, см. locks) |
| `getTtl(key)` → `Long` | Прочитать оставшийся TTL (миллисекунды) |
| `lock(key, level, ...)` → `LockStatus` | Лок READ/WRITE/GLOBAL, только владелец управляет. **TTL лока ≠ TTL данных** — см. [locks_ru.md](locks_ru.md) |
| `unlock(key, ...)` → `LockStatus` | Снять лок (только владелец) |
| `remove(key)` → `Boolean` | Удалить объект или контейнер целиком |

Все методы асинхронны (`CompletableFuture`), принимают необязательный `KeyHintData`
(подсказка роутинга/placement в кластере), `clientId` и `timeout`. У перегрузок по
умолчанию они подставляются из настроек клиента.
