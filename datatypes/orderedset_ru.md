# OrderedSet (взвешенное множество)

Множество, где каждый элемент имеет вес (uint64). Элементы упорядочены по весу; один и
тот же элемент может встречаться с разными весами — такого в Redis нет. Диапазонные
чтения по весу выполняются на сервере.

Применение: рейтинги, приоритетные очереди, leader board'ы, отложенные задачи
(вес = время исполнения).

## Java API

| Метод | Что делает | Применение |
|---|---|---|
| `createOrderedSet(key, entries, ttl?)` → `KeyHintData` | Создать с начальным содержимым | |
| `addElementWithWeight(key, weight, value)` → `Integer` | Добавить элемент с весом | Аналог `ZADD` |
| `incrementOrder(key, OrderedPayload(value, curWeight), delta)` → `Boolean` | Атомарный инкремент/декремент веса; выход за uint64 → `OUT_OF_RANGE` | Аналог `ZINCRBY` по gRPC; элемент адресуется точным (значение+вес) |
| `update(key, OrderedPayload(value, curWeight), newWeight)` → `Boolean` | Установить абсолютный вес | Переупорядочивание элемента |
| `getOrderedSet(key)` → `List<OrderedVariant>` | Прочитать всё, упорядоченно по весу | Аналог `ZRANGE` |
| `getElementInRangeOrderedSet(key, from, to)` → `List<OrderedVariant>` | Диапазон по весу | Аналог `ZRANGEBYSCORE`; «задачи на ближайший час» |
| `getAndDeleteElementInRangeOrderedSet(key, from, to)` → `List<OrderedVariant>` | Атомарно прочитать и удалить диапазон | Аналог `ZPOPMIN/MAX`; забрать просроченные задачи |
| `getElementWithWeight(key, weight)` → `Variant` | Найти элемент по весу | Аналог чтения по score |
| `getAndRemoveElementWithWeight(key, weight)` → `Variant` | Атомарно забрать по весу | |
| `addInContainer(key, member, delta)` → `Number` | Атомарный инкремент по элементу | Аналог `ZINCRBY` |
| `getSize(key)` → `Integer` | Количество элементов | Аналог `ZCARD` |

## Замечания

- `OrderedVariant` несёт и значение, и вес (`getOrder()`).
- Повторное добавление того же значения с другим весом создаёт отдельный элемент —
  дубликаты по весу допустимы (отличие от Redis, где score перезаписывается).
- TTL назначается на контейнер целиком.
- Если кроме веса нужен payload у каждого члена — смотрите OrderedMap.
