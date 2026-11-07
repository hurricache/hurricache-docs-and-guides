# ArrayList (vector)

Список с произвольным доступом по индексу. Помимо операций с концами поддерживает
чтение, вставку, замену и удаление по позиции.

Применение: упорядоченные коллекции с доступом по позиции, ринг-буферы, таблицы
состояния по индексу.

## Java API

| Метод | Что делает | Применение |
|---|---|---|
| `createVector(key, values, ttl?)` → `KeyHintData` | Создать вектор с начальным содержимым | |
| `getVector(key)` → `List<Variant>` | Прочитать целиком | |
| `getAt(key, pos)` → `Variant` | Чтение по индексу | Аналог `LINDEX` |
| `getRange(key, from, to)` → `List<Variant>` | Чтение диапазона | Аналог `LRANGE` |
| `insertAt(key, pos, value)` → `Integer` | Вставка по позиции | |
| `insertBefore(key, pos, value)` / `insertAfter(key, pos, value)` → `Integer` | Вставка до/после позиции | Аналог `LINSERT` |
| `exchangeInContainer(key, pos, value)` → `Number` | Замена по индексу | Аналог `LSET` |
| `removeAt(key, pos)` → `Integer` | Удаление по позиции | |
| `popAt(key, pos)` → `Variant` | Атомарно прочитать и удалить по позиции | |
| `getAndDeleteVectorElementsInRange(key, from, to)` → `List<Variant>` | Прочитать и удалить диапазон | |
| `pushHead` / `pushTail` / `popHead` / `popTail` | Операции с концами | Как у LinkedList |
| `getSize(key)` → `Integer` | Длина | |

## Замечания

- Вставка/удаление в середине — O(n); для интенсивных операций с концами лучше LinkedList.
- TTL назначается на контейнер целиком.
