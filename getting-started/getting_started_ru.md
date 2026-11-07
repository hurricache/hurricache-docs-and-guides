# Getting Started: HurriCache за 5 минут

Путь: поднять standalone-ноду → подключить Java-клиент → первая запись, чтение и CAS.

## 1. Запуск ноды

```bash
docker run -d --name hurricache-dev \
  -p 127.0.0.1:50000:50000 \
  alexaborisov/fastcache-standalone-noavx512:latest
```

gRPC API слушает `127.0.0.1:50000`. Конфиг не нужен — дефолтный вшит в образ
(1024 партиции, RESP-коннектор на 6379, снапшоты включены).

## 2. Подключение (Java)

```java
import com.hurricache.client.FastCacheAsyncStandaloneClient;
import com.hurricache.client.intf.Variant;
import java.time.Duration;

var cache = new FastCacheAsyncStandaloneClient(
        "127.0.0.1", 50000, 0, Duration.ofSeconds(5));
```

Все вызовы возвращают `CompletableFuture` — дальше `.get()` для простоты или
конвейер из futures для продакшна.

## 3. Первая запись и чтение

```java
// Строгое создание: повторный create того же ключа — ошибка
cache.create("user:1:visits", Variant.ofLong(1)).get();

Variant v = cache.get("user:1:visits").get();
System.out.println(v.longValue()); // 1 — это int64, не строка
```

## 4. Атомики и CAS

```java
cache.add("user:1:visits", null, 1).get();          // 2

// Оптимистичный переход состояния — один RPC вместо WATCH/MULTI/EXEC
var res = cache.compareAndSet("user:1:visits", 2, 10).get();
System.out.println(res.getResult());                // true, если текущее значение было 2
```

## 5. Контейнер

```java
cache.createQueue("jobs", java.util.List.of(Variant.ofBytes("job-1".getBytes()))).get();
cache.pushTail("jobs", java.util.List.of(Variant.ofBytes("job-2".getBytes()))).get();

Variant job = cache.popHead("jobs").get(); // атомарно: задачу получит ровно один воркер
```

## 6. TTL и persistence

```java
cache.setTtl("user:1:visits", 300_000).get(); // TTL данных — миллисекунды
System.out.println(cache.getTtl("user:1:visits").get());
```

Снапшоты в standalone-образе включены по умолчанию: запишите ключ, перезапустите
контейнер (`docker restart hurricache-dev`), прочитайте ключ — он на месте.

## Дальше

- Типы данных и их API: [../datatypes](../datatypes/README.md)
- Маппинг Redis-команд: [../redis-mapping](../redis-mapping/README.md)
- Кластер за 3 контейнера: [../cluster-compose](../cluster-compose)
