# TLS в HurriCache

HurriCache умеет поднимать TLS на всех сетевых сервисах. TLS включается
**посервисно** через секцию `tls` в YAML-конфиге: если для сервиса задана пара
`cert` + `key`, его порт работает по TLS; если не задана — порт работает в
открытом виде, как раньше. **Одновременно TLS и не-TLS на одном порту не
поддерживается.**

## Четыре независимых сервиса

| Подсекция `tls` | Что защищает | Кто подключается |
|---|---|---|
| `grpc` | cache gRPC-порт (`cache_port`) | gRPC-клиенты, redis proxy relay |
| `replica` | порт репликации (`replica.port`) | replication-клиенты других узлов |
| `coordinator` | порт координатора (`coordinator_port`) | узлы кластера (announce, ping, routing) |
| `redis_proxy` | RESP-порт (`redis_connector.port`) | Redis-клиенты (rediss://) |

Внутрикластерные клиенты (узел → координатор, replication-клиент,
relay redis proxy → cache gRPC) автоматически используют TLS из той же секции —
отдельной клиентской конфигурации не требуется.

## Формат конфигурации

```yaml
tls:
  grpc:
    cert: /etc/hurricache/tls/grpc-cert.pem    # PEM-цепочка сертификата
    key:  /etc/hurricache/tls/grpc-key.pem     # PEM приватный ключ
    ca:   /etc/hurricache/tls/ca.pem           # опционально: корневой CA
  replica:
    cert: /etc/hurricache/tls/replica-cert.pem
    key:  /etc/hurricache/tls/replica-key.pem
    ca:   /etc/hurricache/tls/ca.pem
  coordinator:
    cert: /etc/hurricache/tls/coordinator-cert.pem
    key:  /etc/hurricache/tls/coordinator-key.pem
    ca:   /etc/hurricache/tls/ca.pem
  redis_proxy:
    cert: /etc/hurricache/tls/redis-cert.pem
    key:  /etc/hurricache/tls/redis-key.pem
```

Правила:

- `cert` и `key` задаются только вместе — иначе конфиг не загрузится.
- `ca` опционален. Если `ca` задан:
  - **сервер** требует от клиентов сертификат, подписанный этим CA (mTLS);
  - **клиент** проверяет серверный сертификат по этому CA. Имя хоста (CN/SAN)
    при этом **не проверяется** — для внутренних кластеров с самоподписанными
    сертификатами это не нужно.
- Если `ca` не задан — только серверный TLS: трафик шифруется, но клиент
  сервер не аутентифицирует, а сервер клиента — тем более.
- Подсекцию можно опустить целиком — сервис останется без TLS.
- Файлы должны существовать, быть читаемыми и непустыми — проверяется при
  загрузке конфига.
- Статус TLS виден в логе при старте (дамп конфига, строки `TLS: ...`).

> Метрикс-порт (`cache_stat_port`, Prometheus) TLS не покрывает.

## Генерация сертификатов (тестовый CA)

Минимальный набор — свой CA и по одному сертификату на сервис:

```bash
mkdir -p /etc/hurricache/tls && cd /etc/hurricache/tls

# Корневой CA
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout ca-key.pem -out ca.pem -days 3650 -subj "/CN=hurricache-ca"

# По сертификату на каждый сервис
for svc in grpc replica coordinator redis; do
  openssl req -newkey rsa:2048 -nodes \
    -keyout $svc-key.pem -out $svc.csr -subj "/CN=$svc.hurricache"
  openssl x509 -req -in $svc.csr -CA ca.pem -CAkey ca-key.pem \
    -CAcreateserial -out $svc-cert.pem -days 365
done

chmod 600 *-key.pem ca-key.pem
```

Так как hostname не проверяется, CN может быть любым. Для mTLS узлы кластера
подписываются тем же CA.

## Запуск standalone с TLS

Минимальный конфиг: TLS на gRPC-порту и на redis proxy, без mTLS (без `ca`):

```yaml
cache_server_ip: 0.0.0.0
cache_port: 50000
storage_directory: /var/lib/hurricache

redis_connector:
  port: 6379
  workers: 2

tls:
  grpc:
    cert: /etc/hurricache/tls/grpc-cert.pem
    key:  /etc/hurricache/tls/grpc-key.pem
  redis_proxy:
    cert: /etc/hurricache/tls/redis-cert.pem
    key:  /etc/hurricache/tls/redis-key.pem
```

Запуск как обычно:

```bash
server-fastcache-standalone -config-file /etc/hurricache/config.yaml
```

## Запуск кластера с TLS

На **каждом** узле добавляется секция `tls` с подсекциями `grpc`, `replica`,
`coordinator` (и `redis_proxy`, если redis-коннектор включён):

```yaml
# ... обычные параметры кластера (cluster:, cache_port, replica, ...) ...

tls:
  grpc:
    cert: /etc/hurricache/tls/grpc-cert.pem
    key:  /etc/hurricache/tls/grpc-key.pem
    ca:   /etc/hurricache/tls/ca.pem
  replica:
    cert: /etc/hurricache/tls/replica-cert.pem
    key:  /etc/hurricache/tls/replica-key.pem
    ca:   /etc/hurricache/tls/ca.pem
  coordinator:
    cert: /etc/hurricache/tls/coordinator-cert.pem
    key:  /etc/hurricache/tls/coordinator-key.pem
    ca:   /etc/hurricache/tls/ca.pem
  redis_proxy:
    cert: /etc/hurricache/tls/redis-cert.pem
    key:  /etc/hurricache/tls/redis-key.pem
    ca:   /etc/hurricache/tls/ca.pem
```

Важно:

- **TLS-конфигурация должна совпадать на всех узлах.** Узел с TLS не сможет
  соединиться с узлом без TLS (и наоборот) — кластер не соберётся. Раскатка
  TLS на живой кластер — это остановка и перезапуск всех узлов с новым
  конфигом.
- Для mTLS (когда задан `ca`) каждый узел предъявляет свой сертификат,
  подписанный общим CA. Можно использовать один и тот же сертификат на всех
  узлах или выдать каждому свой — hostname всё равно не проверяется.
- Разные сервисы могут использовать один и тот же файл сертификата, но
  раздельные сертификаты упрощают ротацию и отзыв.

## Подключение клиентов

### Redis-клиенты (RESP)

Redis proxy с TLS — это обычный rediss:// endpoint:

```bash
redis-cli -p 6379 --tls --cacert /etc/hurricache/tls/ca.pem PING
# без проверки сертификата (если ca не используется):
redis-cli -p 6379 --tls --insecure PING
```

### gRPC-клиенты

Клиент должен использовать TLS-канал. Без `ca` в конфиге сервера — достаточно
TLS без верификации; с `ca` — клиент обязан предъявить сертификат,
подписанный тем же CA, и верифицировать сервер по CA. Проверку hostname
со стороны клиента нужно отключать (сервер её не поддерживает).

Для Java: `ManagedChannelBuilder.useTransportSecurity()` + кастомный
`TrustManager`/`KeyManager` (Netty `SslContextBuilder`):
`GrpcSslContexts.forClient().trustManager(ca) [.keyManager(cert, key)]`, без
hostname verification (`hostnameVerifier` в Netty отключается через
`GrpcSslContexts` + `endpointIdentificationAlgorithm(null)`).

### Клиентские библиотеки

TLS поддерживается во всех четырёх клиентах — API зеркалирует серверную
семантику (`ca` — верификация по CA, `cert`+`key` — клиентский сертификат для
mTLS, опциональный override имени сервера):

- **Go** (`hurricache-go-client`): `hc.TLSConfig{CAFile, CertFile, KeyFile, ServerName}.Load()` → `Config.TLS`.
  Без `ServerName` hostname не проверяется (chain-only верификация).
- **Python** (`hurricache-python-client`): `hurricache.TlsConfig(ca_file, cert_file, key_file, server_name)`
  → параметр `tls=` всех клиентов (sync/async/smart). Внимание: grpc-python
  не умеет шифрование без верификации — `ca_file` обязателен.
- **Java** (`hurricache-java-client`): `TlsConfig.builder().caFile(...).certFile(...).keyFile(...).serverName(...)`.
  Перегрузки конструкторов в `FastCacheAsyncSimpleClient`,
  `FastCacheAsyncStandaloneClient`, `FastCacheAsyncSmartClient`.
  Для сертификатов, чей CN не совпадает с хостом, передавайте `serverName`.
- **C++** (`hurricache-cpp-client`): `TlsConfig{ca_file, cert_file, key_file}`
  — дополнительный параметр конструкторов `FastCacheStandaloneClient` и
  `FastCacheSmartClient`; hostname не проверяется.

## Проверка после запуска

```bash
# gRPC-порты (cache 50000, replica 52000, coordinator 51000):
openssl s_client -connect 127.0.0.1:50000 -CAfile /etc/hurricache/tls/ca.pem -alpn h2 </dev/null
# Ожидается: "New, TLSv1.3, Cipher is ...", "ALPN protocol: h2"

# С mTLS — клиентский сертификат обязателен:
openssl s_client -connect 127.0.0.1:50000 -CAfile ca.pem -alpn h2 \
  -cert client-cert.pem -key client-key.pem </dev/null

# RESP-порт:
redis-cli -p 6379 --tls --cacert /etc/hurricache/tls/ca.pem PING   # PONG

# Plain-подключение к TLS-порту должно обрываться:
redis-cli -p 6379 PING   # Protocol error — это норма для TLS-порта
```

## Замеры производительности (27.11.2026)

Конфигурация стенда:

- 3 ноды на одной машине (192.168.0.123), release-сборка `cmake-build-releasecluster`;
- TLS с mTLS на всех четырёх сервисах (`tls.grpc`, `tls.replica`, `tls.coordinator`, `tls.redis_proxy`),
  тестовый self-signed CA, сертификаты сервисов с общим CN (`hurricache`) —
  конфиги `config-tls-cluster-{0,1,2}.yaml`, сертификаты `tls-test-certs/` в репо сервера;
- запуск кластера: `./start_cluster_tls` (обёртка над конфигами + `HURRICACHE_WORKER_CORES`);
- Java-клиент: `hurricache-java-client`, `TlsConfig` с `caFile` + `certFile/keyFile` (mTLS)
  и `serverName="hurricache"`; JVM: `-XX:+UseZGC -Xmx8g`.

Команда прогона:

```bash
mvn test -Dtest='Smart*Stress*' \
  -Dhurricache.tls.ca=$T/ca.pem \
  -Dhurricache.tls.cert=$T/client-cert.pem \
  -Dhurricache.tls.key=$T/client-key.pem \
  -Dhurricache.tls.serverName=hurricache \
  -DargLine="-XX:+UseZGC -Xmx8g"
```

Итог прогона (164 теста, ~17 мин):

- **162/164 PASS**, 2 ошибки `CANCELLED` в `SmartClientScalarGetMetricsStressTest`
  (гонка/таймаут, не связано с TLS — на рассмотрении);
- throughput: скаляры/атомики/range ~95-140k OPS; контейнерные write-операции
  (insert/removeAt) ~16-75k OPS; bulk ~50-120k OPS;
- latency: p50 0.8-3.2 мс на чтениях и простых записи, p95 1.6-12.8 мс;
### Сравнение TLS vs plaintext (тот же стенд, тот же прогон)

| Класс нагрузки | TLS, OPS | plaintext, OPS | Дельта |
|---|---|---|---|
| Скаляры/атомики (reads) | ~86-130k | ~84-150k | −5..−15% |
| Скаляры/атомики (writes) | ~77-123k | ~81-134k | −3..−12% |
| Range/Ordered | ~80-124k | ~85-139k | −5..−20% |
| Контейнерные write (insert/removeAt) | ~16-75k | ~18-80k | −3..−23% |
| Bulk | ~50-122k | ~51-125k | −1..−7% |

- plaintext-прогон: **164/164 PASS, 0 errors** (в т.ч. `SmartClientScalarGetMetricsStressTest`
  28/28 — два CANCELLED из TLS-прогона не воспроизвелись);
- типичный дельта-эффект TLS на этом стенде: **~5-15% по OPS**,
  p50 растёт с ~0.8 до ~1.6 мс на многих read-операциях (нагрузка localhost,
  базовая latency маленькая — относительный overhead заметнее);
- handshake платится один раз на канал, не на RPC; overhead — цена
  шифрования/расшифровки потока (BoringSSL AES/ChaCha) на обеих сторонах.
  На нагрузке с более крупными payload'ами или при сетевых задержках относительный
  overhead будет ниже.

## Диагностика

| Симптом | Причина |
|---|---|
| `Config error: 'tls.X': 'cert' and 'key' must be set together` | задан только один из пары |
| `Config error: 'tls.X': file '...' is not readable or empty` | нет файла/прав, пустой файл |
| `TLS [X]: invalid identity key/cert pair` при старте | ключ не соответствует сертификату или битый PEM |
| Кластер не собирается после включения TLS | TLS-секции на узлах различаются (где-то TLS, где-то нет) |
| `remote error: tls: certificate required` / обрыв после handshake | на сервере задан `ca` (mTLS), а клиент без сертификата |
| Клиент ругается на hostname/CN | отключите проверку hostname на клиенте — сервер её не поддерживает |

## Ограничения

- TLS и не-TLS на одном порту одновременно не поддерживается.
- Ротация сертификатов — через перезапуск узла (файлы читаются при старте).
- TLS 1.2+ (по умолчанию согласуется TLS 1.3).
- Метрикс-порт Prometheus остаётся без TLS — ограничивайте его сетевыми
  средствами (firewall, bind на loopback).
