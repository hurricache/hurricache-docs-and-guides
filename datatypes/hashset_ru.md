# HashSet

Классическое множество уникальных типизированных значений. Атомарные операции
добавления/удаления членов на сервере.

Применение: теги, уникальные идентификаторы, белые/чёрные списки, membership-проверки.

## Java API

| Метод | Что делает | Применение |
|---|---|---|
| `createSet(key, members, ttl?)` → `KeyHintData` | Создать множество с начальным содержимым | |
| `addElementUnordered(key, value)` → `Integer` | Добавить члена | Аналог `SADD` |
| `getSet(key)` → `List<Variant>` | Прочитать всё множество | Аналог `SMEMBERS` |
| `containsKey(key, member)` → `Boolean` | Проверка членства | Аналог `SISMEMBER` |
| `removeFromContainerByKeyAndValue(key, member)` → `Integer` | Удалить члена | Аналог `SREM` |
| `popInContainer(key, member)` → `Variant` | Атомарно забрать члена | Аналог `SPOP` по конкретному члену |
| `getSize(key)` → `Integer` | Мощность множества | Аналог `SCARD` |

## Замечания

- Операции над множествами (`SINTER`/`SUNION`/`SDIFF`) выполняются на клиенте из `getSet`.
- Дубликаты отбрасываются — множество хранит уникальные значения.
- TTL назначается на контейнер целиком.
- Если нужны веса у членов — смотрите OrderedSet.
