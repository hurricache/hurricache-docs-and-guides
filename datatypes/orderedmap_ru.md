# OrderedMap (вес + payload)

Фирменный контейнер HurriCache: ключ + вес + payload в одной структуре с общим TTL.
Заменяет идиому Redis «sorted set для порядка + hash для данных» — порядок и содержимое
обновляются одной операцией и живут под одним TTL, без прикладного клея между двумя
ключами.

Применение: кандидаты маршрутизации с весом и метаданными, прайс-листы, рейтинги
с атрибутами, отложенные задачи с телом.

## Java API

| Метод | Что делает | Применение |
|---|---|---|
| `createOrderedMap(key, map, ttl?)` → `KeyHintData` | Создать с начальным содержимым (`OrderedPayload` = ключ+вес → `Variant`) | |
| `addElementOrderedMap(key, orderedKey, value)` → `Integer` | Добавить/обновить элемент | |
| `incrementOrder(key, OrderedPayload(key, curWeight), delta)` → `Boolean` | Атомарный инкремент/декремент веса, payload сохраняется; выход за uint64 → `OUT_OF_RANGE` | Рейтинги/приоритеты с атрибутами |
| `update(key, OrderedPayload(key, curWeight), newWeight)` → `Boolean` | Абсолютный вес, payload сохраняется | Переупорядочивание без потери данных |
| `getOrderedMap(key)` → `Map<OrderedPayload, Variant>` | Прочитать целиком | |
| `getElementInRangeOrderedMap(key, from, to)` → `Map<OrderedPayload, Variant>` | Диапазон по весу | Топ-N кандидатов, выборка по приоритету |
| `getAndDeleteElementInRangeOrderedMap(key, from, to)` → `Map<...>` | Атомарно прочитать и удалить диапазон | Забрать готовые задачи с телами |
| `getInContainer(key, orderedKey)` → `Variant` | Точечное чтение payload | |
| `removeFromContainerByKey(key, orderedKey)` → `Integer` | Удалить элемент | |
| `addInContainer` / остальные `*InContainer` | Атомики по полям | Счётчики внутри элементов |
| `getSize(key)` → `Integer` | Количество элементов | |

## Замечания

- `OrderedPayload` объединяет ключ элемента и его вес — элемент адресуется парой
  (ключ, вес).
- Один TTL на контейнер: порядок и payload не расходятся по времени жизни.
- Вес — uint64; порядок чтения — по возрастанию веса.
