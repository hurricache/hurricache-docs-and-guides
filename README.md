# HurriCache — документация и гайды

| Раздел | Содержание |
|---|---|
| [getting-started](getting-started) | Быстрый старт: docker → Java-клиент → первая запись и CAS (RU/EN) |
| [datatypes](datatypes) | Типы данных: скаляры, контейнеры, локи; API каждого типа (RU/EN) |
| [redis-mapping](redis-mapping) | Маппинг Redis-команд на Java API, кластерный relay/MOVED, placement (RU/EN) |
| [error-handling](error-handling) | Коды ошибок gRPC, «нет ключа» vs «пусто», ретраи (RU/EN) |
| [cluster-client](cluster-client) | Smart-клиент: режимы MASTER/BACKUP, топология, KeyHintData placement (RU/EN) |
| [operations](operations) | Метрики, снапшоты, эксплуатация кластера, лицензии (RU/EN) |
| [server-config](server-config) | Конфигурация сервера: все параметры YAML-конфига, примеры для standalone и кластера (RU/EN) |
| [lifecycle](lifecycle) | TTL данных (мс) vs TTL лока (с) vs stability window, удаление (RU/EN) |
| [performance](performance) | Производительность клиента: futures, сжатие, хинты, таймауты (RU/EN) |
| [migration](migration) | Пошаговая миграция с Redis, грабли (RU/EN) |
| [clients](clients) | Описание gRPC API для авторов клиентов (RU/EN) |
| [cluster-compose](cluster-compose) | Готовый docker-compose 3-нодного кластера + генератор конфигов |
| [charging-emulator](charging-emulator) | Нагрузочный эмулятор чарджинга с контролем минуса: HurriCache (CAS) vs PostgreSQL |
| [dashboards](dashboards) | Дашборд Grafana |
