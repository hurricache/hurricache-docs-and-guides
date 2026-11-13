# Charging emulator: HurriCache vs PostgreSQL

[README на русском](README_ru.md)

A load emulator of telecom charging with a hard overdraft guard.
The same workload runs through two engines behind the `ChargingEngine` interface:

- `HurriCacheChargingEngine` — the balance is a typed int64 scalar (`balance:<msisdn>`);
  a conditional debit is a CAS loop "read → check sufficiency → compareAndSet";
  the loser of a race retries instantly, without locks. A refund is an atomic `add`.
- `PostgresChargingEngine` — a single atomic `UPDATE ... WHERE balance >= ?`;
  on a race for a hot row the contenders wait on the row lock.

The emulator models hierarchical accounts (some subscribers are attached to parent
balances — see `--parents-percent`), parallel debits across N threads and random
rollbacks, and prints latency statistics at the end of the run.

## Build

The emulator depends on the HurriCache Java client, which is not published to Maven
Central — build it locally first:

```bash
git clone https://github.com/hurricache/hurricache-java-client
cd hurricache-java-client && mvn install -DskipTests && cd ..

cd charging-emulator
mvn compile
```

## Run

Against a HurriCache cluster (default seeds `192.168.0.123:51000,61000,21000`,
override with `--seeds`):

```bash
./run-hurricache.sh                      # 100k accounts, 8 threads, 60 seconds
./run-hurricache.sh --threads 32 --parents-percent 50
```

Against PostgreSQL (expects a `benchmark` database with user `benchmark`/`benchmark`,
override with `--db-url/--db-user/--db-pass`):

```bash
./run-postgres.sh
```

Balance provisioning runs automatically (`--provision` is already in the scripts);
after the run the `balance:*` keys are removed from the cluster (disable: `--keep-data`).

## Parameters

| Argument | Default | Description |
|---|---|---|
| `--mode` | `postgres` | `hurricache` or `postgres` |
| `--accounts` | `100000` | Number of subscriber accounts |
| `--initial-balance` | `10000` | Initial balance |
| `--parents-percent` | `20` | Share of accounts with a parent balance (hierarchy) |
| `--threads` | `16` | Load threads |
| `--duration-sec` | `60` | Run duration |
| `--charge-max` | `100` | Maximum amount of a single charge |
| `--rollback-chance` | `0.3` | Probability of a refund after a charge |
| `--provision` | off | Provision balances before the run |
| `--keep-data` | off | Do not delete keys after the run |
| `--seeds` | see above | HurriCache cluster node addresses, comma-separated |
| `--db-url` / `--db-user` / `--db-pass` | `jdbc:postgresql://localhost:5432/benchmark` | PostgreSQL connection |
