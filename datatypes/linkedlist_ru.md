# LinkedList

Двусвязный список типизированных значений. Эффективные push/pop с обоих концов
(O(1)), атомарные — подходят для очередей и стеков.

Применение: очереди задач, стеки, журналы событий с двусторонним доступом.

## Java API

| Метод | Что делает | Применение |
|---|---|---|
| `createList(key, values, ttl?)` → `KeyHintData` | Создать список с начальным содержимым | |
| `getList(key)` → `List<Variant>` | Прочитать весь список | Полная выгрузка |
| `pushHead(key, values)` → `Integer` | Добавить в голову | Стек (LIFO) вместе с `popHead` |
| `pushTail(key, values)` → `Integer` | Добавить в хвост | Очередь (FIFO) вместе с `popHead` |
| `popHead(key)` → `Variant` | Атомарно забрать из головы | Гонко-free назначение задач |
| `popTail(key)` → `Variant` | Атомарно забрать из хвоста | |
| `peekHead(key)` / `peekTail(key)` → `Variant` | Прочитать без удаления | |
| `deleteHead(count)` / `deleteTail(count)` → `Integer` | Удалить N элементов с конца | Обрезка журнала |
| `getAndDeleteListElementsInRange(key, from, to)` → `List<Variant>` | Прочитать и удалить диапазон | Пакетное забор элементов |
| `removeAt(key, pos)` → `Integer` | Удалить по позиции | |
| `getSize(key)` → `Integer` | Длина списка | |

## Замечания

- Все pop/push атомарны на сервере — два клиента не заберут один элемент.
- TTL назначается на контейнер целиком.
- Если нужен произвольный доступ по индексу — смотрите ArrayList.
