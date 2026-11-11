# Конфигурация сервера HurriCache

Сервер кеша (standalone и кластерный) настраивается одним YAML-файлом, путь к
которому передаётся при запуске. Формат одинаков для обоих режимов: разница
только в наличии секции `cluster`.

Если параметр не указан, используется значение по умолчанию. Обязательные
поля — только `cache_server_ip` и `cache_port`.

## Пример: standalone (без кластера)

```yaml
cache_server_ip: 0.0.0.0
cache_port: 50000

partitions: 1024
num_threads: 2
cpu_pin_offset: 0

storage_directory: /var/lib/hurricache
snapshot_interval_sec: 300
snapshot_change_threshold: 1000

server_grpc_channel_args:
  - "grpc.max_concurrent_streams=-1i"
  - "grpc.enable_channelz=0i"
  - "grpc.enable_census=0i"
  - "grpc.enable_load_reporting=0i"
  - "grpc.server_call_metric_recording=0i"
  - "grpc.tcp_tx_zerocopy_enabled=1i"
  - "grpc.optimization_target=latencys"

redis_connector:
  port: 6379
  workers: 2
```

## Пример: кластер (3 ноды)

Конфиг одной из нод. Секция `cluster` содержит **все** ноды кластера
(включая текущую) и должна быть одинаковой на всех нодах.

```yaml
cluster:
  - target_ip: 192.168.0.11
    target_port: 50000
    coordinator_ip: 192.168.0.11
    coordinator_port: 51000
    replicator_ip: 192.168.0.11
    replicator_port: 52000
  - target_ip: 192.168.0.12
    target_port: 50000
    coordinator_ip: 192.168.0.12
    coordinator_port: 51000
    replicator_ip: 192.168.0.12
    replicator_port: 52000
  - target_ip: 192.168.0.13
    target_port: 50000
    coordinator_ip: 192.168.0.13
    coordinator_port: 51000
    replicator_ip: 192.168.0.13
    replicator_port: 52000

cluster_id: prod-cache-1
license_file: /etc/hurricache/license.lic

cache_server_ip: 192.168.0.11
cache_port: 50000
coordinator_ip: 192.168.0.11
coordinator_port: 51000
cache_stat_port: 8080

partitions: 1024
num_threads: 4
cpu_pin_offset: 0

heartbeat: 5
hello_delay: 10
force_start_delay: 20

storage_directory: /var/lib/hurricache
snapshot_interval_sec: 300
snapshot_change_threshold: 1000

replica:
  ip: 192.168.0.11
  port: 52000
  num_rep_threads: 2
  cpu_pin_offset: 8

server_grpc_channel_args:
  - "grpc.max_concurrent_streams=-1i"
  - "grpc.tcp_tx_zerocopy_enabled=1i"
  - "grpc.optimization_target=latencys"

replication_grpc_channel_args:
  - "grpc.max_concurrent_streams=-1i"
  - "grpc.optimization_target=throughputs"
```

## Справочник параметров

### Обязательные

| Параметр | Тип | Описание |
|---|---|---|
| `cache_server_ip` | string | IP-адрес, на котором сервер слушает клиентские gRPC-подключения. `0.0.0.0` — все интерфейсы |
| `cache_port` | int | Порт клиентского gRPC-интерфейса |

### Секция `cluster` (только кластерный режим)

Список нод кластера. Для standalone-режима секция опускается целиком.

| Поле ноды | Тип | По умолчанию | Описание |
|---|---|---|---|
| `target_ip` | string | — (обязательно) | IP ноды (клиентский интерфейс) |
| `target_port` | int | — (обязательно) | Клиентский порт ноды |
| `coordinator_ip` | string | `127.0.0.1` | IP координатора ноды |
| `coordinator_port` | int | `60000` | Порт координатора ноды |
| `replicator_ip` | string | `127.0.0.1` | IP сервиса репликации ноды |
| `replicator_port` | int | `51000` | Порт сервиса репликации ноды |
| `redis_port` | int | не задан | Redis-совместимый порт ноды (опционально) |

### Координатор и идентификация

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `cluster_id` | string | `""` | Идентификатор кластера |
| `license_file` | string | `""` | Путь к файлу лицензии (применяется и к кластеру, и к standalone) |
| `coordinator_ip` | string | `127.0.0.1` | IP локального координатора |
| `coordinator_port` | int | `60000` | Порт локального координатора |
| `cache_stat_port` | int | `8080` | Порт HTTP-эндпоинта метрик (Prometheus `/metrics`) |

### Тайминги кластера

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `heartbeat` | int (сек) | `10` | Интервал heartbeat между нодами |
| `hello_delay` | int (сек) | `2` | Задержка перед рассылкой hello при старте |
| `force_start_delay` | int (сек) | `15` | Задержка принудительного старта координатора, если кластер не собрался |

### Производительность

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `partitions` | int | `1024` | Число партиций (шардов). Должно быть степенью двойки |
| `num_threads` | int | `4` | Число рабочих потоков обработки запросов |
| `cpu_pin_offset` | int | `-1` (выкл.) | Смещение CPU для привязки рабочих потоков (pinning) |

### Хранилище и снапшоты

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `storage_directory` | string | `/tmp/hurricache` | Каталог для файлов снапшотов |
| `snapshot_interval_sec` | int (сек) | `300` | Интервал проверки необходимости снапшота |
| `snapshot_change_threshold` | int | `1000` | Минимальное число изменившихся операций для записи снапшота |

### Секция `replica` (репликация)

Локальный сервис репликации ноды (используется в кластерном режиме).

| Поле | Тип | По умолчанию | Описание |
|---|---|---|---|
| `ip` | string | `127.0.0.1` | IP сервиса репликации |
| `port` | int | `35000` | Порт сервиса репликации |
| `num_rep_threads` | int | `2` | Число потоков репликации |
| `cpu_pin_offset` | int | `-1` (выкл.) | Смещение CPU для потоков репликации |

### Секция `redis_connector` (Redis-совместимый интерфейс)

Опционально. Позволяет подключаться к кешу по протоколу Redis (RESP).

| Поле | Тип | По умолчанию | Описание |
|---|---|---|---|
| `port` | int | `6379` | Порт Redis-интерфейса |
| `workers` | int | `1` | Число рабочих потоков коннектора |
| `workers_relay` | int | = `workers` | Число потоков relay (перенаправление запросов в кластере) |
| `relay_timeout_ms` | int | `1000` | Таймаут relay-запроса, мс |
| `redis_proxy` | bool | `true` | Режим прокси для Redis-команд |

### gRPC-параметры канала

`server_grpc_channel_args` — настройки клиентского gRPC-сервера,
`replication_grpc_channel_args` — настройки межнодовых каналов репликации.

Формат элемента списка: `"ключ=значение"`, где последний символ значения
задаёт тип: суффикс `i` — целое число, `s` — строка. Если суффикса нет,
значение трактуется как строка целиком.

Примеры полезных ключей:

| Ключ | Описание |
|---|---|
| `grpc.max_concurrent_streams` | Максимум одновременных RPC-стримов на соединение (`-1i` — без лимита) |
| `grpc.tcp_tx_zerocopy_enabled` | Zero-copy отправка по TCP (`1i` — вкл.) |
| `grpc.optimization_target` | `latencys` (латентность) или `throughputs` (пропускная способность) |
| `grpc.enable_channelz` / `grpc.enable_census` / `grpc.enable_load_reporting` | Телеметрия gRPC; обычно `0i` в проде |
| `grpc.keepalive_time_ms` | Интервал keepalive-пингов |

## Переменные окружения

### `HURRICACHE_WORKER_CORES`

Список ядер CPU через запятую для пиннинга рабочих потоков gRPC event engine
(только Linux/POSIX). Потоки привязываются к перечисленным ядрам по кругу
(round-robin).

```bash
export HURRICACHE_WORKER_CORES=0,1,2,3
./server-fastcache config.yaml
```

Рекомендуется выделять gRPC-потокам те же ядра, что и рабочим потокам кеша
(см. `cpu_pin_offset` + `num_threads`), либо соседние ядра того же NUMA-узла.
Если переменная не задана, пиннинг не выполняется и потоки планируются ОС.
При старте сервер выводит в лог строку `GRPC will use worker cores: ...`,
по которой видно, что переменная подхвачена.

Переменная работает одинаково в standalone и кластерном режимах.

## Лицензирование

Проверка лицензии выполняется при старте сервера **в обоих режимах** — и в
кластерном, и в standalone. Путь к файлу задаётся параметром `license_file`.
Без валидной лицензии сервер не стартует. Подробнее см. раздел
[operations](../operations).

## Замечания

- Все порты должны быть в диапазоне 1–65535; при ошибках валидации сервер
  отказывается стартовать и печатает причину.
- Секция `cluster` должна быть идентичной на всех нодах — расхождения
  приводят к невозможности собрать кластер.
- Полный набор ключей gRPC см. в документации gRPC core (channel arguments).
