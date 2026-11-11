# HurriCache Server Configuration

The cache server (standalone and clustered) is configured with a single YAML
file passed at startup. The format is the same for both modes; the only
difference is the presence of the `cluster` section.

Every parameter has a default value. Only `cache_server_ip` and `cache_port`
are required.

## Example: standalone (no cluster)

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

## Example: cluster (3 nodes)

Config of one node. The `cluster` section lists **all** cluster nodes
(including the local one) and must be identical on every node.

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

## Parameter reference

### Required

| Parameter | Type | Description |
|---|---|---|
| `cache_server_ip` | string | IP address the client-facing gRPC server binds to. `0.0.0.0` — all interfaces |
| `cache_port` | int | Client-facing gRPC port |

### `cluster` section (cluster mode only)

List of cluster nodes. Omit the whole section for standalone mode.

| Node field | Type | Default | Description |
|---|---|---|---|
| `target_ip` | string | — (required) | Node IP (client interface) |
| `target_port` | int | — (required) | Node client port |
| `coordinator_ip` | string | `127.0.0.1` | Node coordinator IP |
| `coordinator_port` | int | `60000` | Node coordinator port |
| `replicator_ip` | string | `127.0.0.1` | Node replication service IP |
| `replicator_port` | int | `51000` | Node replication service port |
| `redis_port` | int | unset | Redis-compatible port of the node (optional) |

### Coordinator and identity

| Parameter | Type | Default | Description |
|---|---|---|---|
| `cluster_id` | string | `""` | Cluster identifier |
| `license_file` | string | `""` | Path to the license file (applies to both cluster and standalone modes) |
| `coordinator_ip` | string | `127.0.0.1` | Local coordinator IP |
| `coordinator_port` | int | `60000` | Local coordinator port |
| `cache_stat_port` | int | `8080` | HTTP metrics endpoint port (Prometheus `/metrics`) |

### Cluster timings

| Parameter | Type | Default | Description |
|---|---|---|---|
| `heartbeat` | int (sec) | `10` | Heartbeat interval between nodes |
| `hello_delay` | int (sec) | `2` | Delay before sending hello on startup |
| `force_start_delay` | int (sec) | `15` | Forced coordinator start delay if the cluster does not assemble |

### Performance

| Parameter | Type | Default | Description |
|---|---|---|---|
| `partitions` | int | `1024` | Number of partitions (shards). Must be a power of two |
| `num_threads` | int | `4` | Number of worker threads |
| `cpu_pin_offset` | int | `-1` (off) | CPU offset for worker thread pinning |

### Storage and snapshots

| Parameter | Type | Default | Description |
|---|---|---|---|
| `storage_directory` | string | `/tmp/hurricache` | Directory for snapshot files |
| `snapshot_interval_sec` | int (sec) | `300` | Interval between snapshot checks |
| `snapshot_change_threshold` | int | `1000` | Minimum number of changed operations to trigger a snapshot |

### `replica` section (replication)

Local replication service of the node (cluster mode).

| Field | Type | Default | Description |
|---|---|---|---|
| `ip` | string | `127.0.0.1` | Replication service IP |
| `port` | int | `35000` | Replication service port |
| `num_rep_threads` | int | `2` | Number of replication threads |
| `cpu_pin_offset` | int | `-1` (off) | CPU offset for replication threads |

### `redis_connector` section (Redis-compatible interface)

Optional. Allows connecting to the cache via the Redis protocol (RESP).

| Field | Type | Default | Description |
|---|---|---|---|
| `port` | int | `6379` | Redis interface port |
| `workers` | int | `1` | Connector worker threads |
| `workers_relay` | int | = `workers` | Relay threads (request forwarding in a cluster) |
| `relay_timeout_ms` | int | `1000` | Relay request timeout, ms |
| `redis_proxy` | bool | `true` | Proxy mode for Redis commands |

### gRPC channel arguments

`server_grpc_channel_args` tunes the client-facing gRPC server,
`replication_grpc_channel_args` tunes the inter-node replication channels.

List item format: `"key=value"`, where the last character of the value sets
the type: suffix `i` — integer, `s` — string. Without a suffix the whole
value is treated as a string.

Useful keys:

| Key | Description |
|---|---|
| `grpc.max_concurrent_streams` | Max concurrent RPC streams per connection (`-1i` — unlimited) |
| `grpc.tcp_tx_zerocopy_enabled` | TCP zero-copy send (`1i` — on) |
| `grpc.optimization_target` | `latencys` (latency) or `throughputs` (throughput) |
| `grpc.enable_channelz` / `grpc.enable_census` / `grpc.enable_load_reporting` | gRPC telemetry; usually `0i` in production |
| `grpc.keepalive_time_ms` | Keepalive ping interval |

## Environment variables

### `HURRICACHE_WORKER_CORES`

Comma-separated list of CPU cores to pin the gRPC event engine worker
threads to (Linux/POSIX only). Threads are bound to the listed cores
round-robin.

```bash
export HURRICACHE_WORKER_CORES=0,1,2,3
./server-fastcache config.yaml
```

It is recommended to give the gRPC threads the same cores as the cache
worker threads (see `cpu_pin_offset` + `num_threads`), or neighboring cores
on the same NUMA node. If the variable is not set, no pinning is performed
and threads are scheduled by the OS. On startup the server logs
`GRPC will use worker cores: ...`, confirming the variable was picked up.

The variable works the same way in standalone and cluster modes.

## Licensing

The license is checked at server startup in **both modes** — cluster and
standalone. The file path is set by the `license_file` parameter. The server
does not start without a valid license. See the [operations](../operations)
section for details.

## Notes

- All ports must be in the 1–65535 range; on validation errors the server
  refuses to start and prints the reason.
- The `cluster` section must be identical on every node — mismatches prevent
  the cluster from assembling.
- For the full set of gRPC keys see the gRPC core documentation (channel
  arguments).
