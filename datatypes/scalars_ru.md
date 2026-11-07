# Скаляры (int64 / double / byte arrays)

Типизированные атомарные значения. Тип объявляется при создании и проверяется на границе
API: вызов не того типа — ошибка, а не попытка парсинга строки.

Применение: счётчики, флаги состояния, usage-based billing, rate limiting, маленькие
произвольные payload'ы (байты).

## Жизненный цикл

| Java API | Что делает | Применение |
|---|---|---|
| `create(key, value, ttl?)` → `KeyHintData` | Строгое создание; существующий ключ — ошибка | Инициализация состояния; гарантия отсутствия тихой перезаписи |
| `get(key)` → `Variant` | Типизированное чтение | Ответ того же типа, что записан |
| `replace(key, value, ...)` → `Variant` | Обновление только существующего | Отделение обновления от создания |
| `update(key, value, ...)` → `Variant` | Upsert | Эквивалент Redis SET |
| `pop(key)` → `Variant` | Атомарный get-and-delete одним запросом | Забрать результат вычисления ровно один раз |
| `remove(key)` → `Boolean` | Удаление | |

## Атомики (int64 / double)

Каждая операция — один RPC, аппаратная атомарность, без Lua.

| Java API | Что делает | Применение |
|---|---|---|
| `add(key, hint, delta)` → `Number` | fetch-and-add; переполнение → `OUT_OF_RANGE` | Счётчики, лимиты |
| `sub(key, hint, delta)` → `Number` | fetch-and-sub | |
| `exchange(key, hint, value)` → `Number` | Записать новое, вернуть старое | Атомарная смена значения |
| `and` / `or` / `xor` (key, hint, mask) → `Long` | Битовые операции на int64, результат сразу | Битовые флаги состояния |
| `compareAndSet(key, expected, new)` → `AtomicCasRes` | Оптимистичная запись «если X, то Y»; `getResult()` = успех | Распределённые стейт-машины, согласованные переходы состояний без WATCH-циклов |

## Пример: стейт-машина заказа

```java
var cache = new FastCacheAsyncStandaloneClient("127.0.0.1", 50000, 0, Duration.ofSeconds(5));

long NEW = 0, PAID = 1;

cache.create("order:42:state", Variant.ofLong(NEW)).get();

AtomicCasRes res = cache.compareAndSet("order:42:state", NEW, PAID).get();
if (res.getResult()) {
    // переход NEW -> PAID зафиксирован ровно один раз, на весь кластер
}
```
