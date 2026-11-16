# Operations

## Prometheus metrics

- A node exports metrics on `cache_stat_port` (default 8080), path `/metrics`.
- Ready-made Grafana dashboard: [../dashboards/dashboard.json](../dashboards/dashboard.json).
- The compose example brings up Prometheus + Grafana with the cluster:
  [../cluster-compose](../cluster-compose).

What to watch first:

| Metric | What it tells you |
|---|---|
| operation latencies (avg/p99 per operation) | hot-path degradation |
| cache size / key count | data growth, memory pressure |
| snapshot counters | frequency and duration of background flushes |
| per-operation error counters | client/type problems |
| license status | printed in the stdout statistics stream |

## Snapshots

- Two triggers, both configurable: time-based (`interval`, default 300 s) and per-partition
  change count (`change_threshold`, default 1000).
- Snapshots are background-only — they do not affect the request hot path.
- Files are written to `storage_directory`; on restart a node restores its data from them.
- Verify: write a key, restart the container, read the key back — it is still there.

## Cluster operations

- Adding a node is one coordinator API call (automatable over HTTP).
- Hot node replacement: data is copied inside the cluster.
- A 30–120 s stability window absorbs short restarts without false migrations — p99 stays
  steady during deploys.
- A three-node cluster comes up in under a minute.

## CPU pinning

- The `cpu_pinning` config section: explicit core lists per thread group —
  `cache_workers_cpu_ids`, `replication_server_workers_cpu_ids`,
  `replication_client_workers_cpu_ids`, `redis_proxy_workers_cpu_ids` and
  `defer_deleter_cpu_id`. Cores are given as a comma-separated string (`"0,1,2"`)
  or a YAML list; an empty list means no pinning; more threads than cores wraps
  around round-robin. See `server-config/server_config_en.md` for details.
- **`HURRICACHE_WORKER_CORES`** (env, e.g. `HURRICACHE_WORKER_CORES=1,2,3`):
  pins the **internal gRPC event-engine worker threads** (library threads) to the listed
  cores. The server prints a confirmation on startup. Use it to separate the gRPC network
  stack from the cache worker threads.

## Licensing

- Without a license file, community limits apply: up to 3 nodes, 1024 partitions,
  2 worker threads per node.
- A license is an Ed25519-signed YAML file bound to the cluster config fingerprint;
  it is verified at startup, no license server is needed.
- After expiry there is a 30-day soft period with the same limits, then a fall back to
  community limits. No sudden shutdowns.
- Format details: `doc/licensing.md` in the server repository.
