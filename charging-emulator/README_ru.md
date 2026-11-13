# Эмулятор чарджинга: HurriCache vs PostgreSQL

[README in English](README.md)

Нагрузочный эмулятор telecom-чарджинга с контролем ухода баланса в минус.
Один и тот же workload гоняется через два движка за интерфейсом `ChargingEngine`:

- `HurriCacheChargingEngine` — баланс как типизированный int64 скаляр (`balance:<msisdn>`),
  условное списание CAS-циклом «прочитал → проверил достаточность → compareAndSet»;
  проигравший гонку повторяет попытку мгновенно, без блокировок. Возврат — атомарный `add`.
- `PostgresChargingEngine` — один атомарный `UPDATE ... WHERE balance >= ?`;
  при гонке на горячей строке конкуренты ждут блокировку строки.

Эмулятор моделирует иерархические аккаунты (часть абонентов привязана к родительским
балансам — см. `--parents-percent`), параллельные списания в N потоков и случайные
rollback'и, и печатает статистику задержек по итогам прогона.

## Сборка

Эмулятор зависит от Java-клиента HurriCache, который не опубликован в Maven Central —
сначала соберите его локально:

```bash
git clone https://github.com/hurricache/hurricache-java-client
cd hurricache-java-client && mvn install -DskipTests && cd ..

cd charging-emulator
mvn compile
```

## Запуск

Против кластера HurriCache (по умолчанию seeds `192.168.0.123:51000,61000,21000`,
переопределяется `--seeds`):

```bash
./run-hurricache.sh                      # 100k аккаунтов, 8 потоков, 60 секунд
./run-hurricache.sh --threads 32 --parents-percent 50
```

Против PostgreSQL (нужна БД `benchmark` с пользователем `benchmark`/`benchmark`,
переопределяется `--db-url/--db-user/--db-pass`):

```bash
./run-postgres.sh
```

Провижининг балансов выполняется автоматически (`--provision` уже в скриптах),
после прогона ключи `balance:*` удаляются из кластера (отключить: `--keep-data`).

## Параметры

| Аргумент | По умолчанию | Описание |
|---|---|---|
| `--mode` | `postgres` | `hurricache` или `postgres` |
| `--accounts` | `100000` | Число абонентских аккаунтов |
| `--initial-balance` | `10000` | Начальный баланс |
| `--parents-percent` | `20` | Доля аккаунтов с родительским балансом (иерархия) |
| `--threads` | `16` | Потоки нагрузки |
| `--duration-sec` | `60` | Длительность прогона |
| `--charge-max` | `100` | Максимальная сумма одного списания |
| `--rollback-chance` | `0.3` | Вероятность возврата после списания |
| `--provision` | off | Заполнить балансы перед прогоном |
| `--keep-data` | off | Не удалять ключи после прогона |
| `--seeds` | см. выше | Адреса узлов кластера HurriCache через запятую |
| `--db-url` / `--db-user` / `--db-pass` | `jdbc:postgresql://localhost:5432/benchmark` | Подключение к PostgreSQL |
