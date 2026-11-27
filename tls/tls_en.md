# TLS in HurriCache

HurriCache can serve TLS on all of its network services. TLS is enabled
**per service** via the `tls` section of the YAML config: if a service has
both `cert` and `key` set, its port speaks TLS; otherwise the port stays
plain, exactly as before. **TLS and plain traffic on the same port at the
same time is not supported.**

## Four independent services

| `tls` subsection | What it protects | Who connects |
|---|---|---|
| `grpc` | cache gRPC port (`cache_port`) | gRPC clients, redis proxy relay |
| `replica` | replication port (`replica.port`) | replication clients of peer nodes |
| `coordinator` | coordinator port (`coordinator_port`) | cluster nodes (announce, ping, routing) |
| `redis_proxy` | RESP port (`redis_connector.port`) | Redis clients (rediss://) |

Intra-cluster clients (node → coordinator, replication client, redis proxy
relay → cache gRPC) automatically use TLS from the same section — no separate
client configuration is needed.

## Configuration format

```yaml
tls:
  grpc:
    cert: /etc/hurricache/tls/grpc-cert.pem    # PEM certificate chain
    key:  /etc/hurricache/tls/grpc-key.pem     # PEM private key
    ca:   /etc/hurricache/tls/ca.pem           # optional: root CA
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

Rules:

- `cert` and `key` must be set together — otherwise the config fails to load.
- `ca` is optional. When `ca` is set:
  - the **server** requires a client certificate signed by this CA (mTLS);
  - the **client** verifies the server certificate against this CA. The
    hostname (CN/SAN) is **not** verified — unnecessary for internal clusters
    with self-signed certificates.
- Without `ca` — server-side TLS only: traffic is encrypted, but neither side
  is authenticated.
- A subsection may be omitted entirely — that service stays plain.
- Files must exist, be readable and non-empty — checked at config load time.
- TLS status is printed in the startup log (config dump, `TLS: ...` lines).

> The metrics port (`cache_stat_port`, Prometheus) is not covered by TLS.

## Generating certificates (test CA)

Minimal setup — your own CA and one certificate per service:

```bash
mkdir -p /etc/hurricache/tls && cd /etc/hurricache/tls

# Root CA
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout ca-key.pem -out ca.pem -days 3650 -subj "/CN=hurricache-ca"

# One certificate per service
for svc in grpc replica coordinator redis; do
  openssl req -newkey rsa:2048 -nodes \
    -keyout $svc-key.pem -out $svc.csr -subj "/CN=$svc.hurricache"
  openssl x509 -req -in $svc.csr -CA ca.pem -CAkey ca-key.pem \
    -CAcreateserial -out $svc-cert.pem -days 365
done

chmod 600 *-key.pem ca-key.pem
```

Since the hostname is not verified, the CN can be anything. For mTLS, all
cluster nodes are signed by the same CA.

## Starting standalone with TLS

Minimal config: TLS on the gRPC port and on the redis proxy, without mTLS
(no `ca`):

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

Start as usual:

```bash
server-fastcache-standalone -config-file /etc/hurricache/config.yaml
```

## Starting a cluster with TLS

On **every** node add the `tls` section with `grpc`, `replica`,
`coordinator` subsections (and `redis_proxy` if the redis connector is
enabled):

```yaml
# ... regular cluster parameters (cluster:, cache_port, replica, ...) ...

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

Important:

- **The TLS configuration must match on all nodes.** A TLS node cannot
  connect to a plain node (and vice versa) — the cluster will not form.
  Rolling TLS out to a live cluster means stopping and restarting all nodes
  with the new config.
- For mTLS (when `ca` is set) each node presents a certificate signed by the
  shared CA. You may reuse one certificate on all nodes or issue one per
  node — the hostname is not verified anyway.
- Different services may share one certificate file, but separate
  certificates simplify rotation and revocation.

## Connecting clients

### Redis clients (RESP)

A TLS-enabled redis proxy is a regular rediss:// endpoint:

```bash
redis-cli -p 6379 --tls --cacert /etc/hurricache/tls/ca.pem PING
# without certificate verification (when ca is not used):
redis-cli -p 6379 --tls --insecure PING
```

### gRPC clients

The client must use a TLS channel. Without `ca` in the server config, plain
TLS without verification is enough; with `ca`, the client must present a
certificate signed by the same CA and verify the server against the CA.
Client-side hostname verification must be disabled (the server does not
support it).

For Java: `ManagedChannelBuilder.useTransportSecurity()` + a custom
`TrustManager`/`KeyManager` (Netty `SslContextBuilder`):
`GrpcSslContexts.forClient().trustManager(ca) [.keyManager(cert, key)]`, with
hostname verification disabled (`endpointIdentificationAlgorithm(null)`).

### Client libraries

TLS is supported in all four clients — the API mirrors the server-side
semantics (`ca` — verification against the CA, `cert`+`key` — client
certificate for mTLS, optional server-name override):

- **Go** (`hurricache-go-client`): `hc.TLSConfig{CAFile, CertFile, KeyFile, ServerName}.Load()` → `Config.TLS`.
  Without `ServerName` the hostname is not verified (chain-only verification).
- **Python** (`hurricache-python-client`): `hurricache.TlsConfig(ca_file, cert_file, key_file, server_name)`
  → the `tls=` parameter of all clients (sync/async/smart). Note: grpc-python
  cannot do encryption without verification — `ca_file` is mandatory.
- **Java** (`hurricache-java-client`): `TlsConfig.builder().caFile(...).certFile(...).keyFile(...).serverName(...)`.
  Constructor overloads in `FastCacheAsyncSimpleClient`,
  `FastCacheAsyncStandaloneClient`, `FastCacheAsyncSmartClient`.
  For certificates whose CN does not match the host, pass `serverName`.
- **C++** (`hurricache-cpp-client`): `TlsConfig{ca_file, cert_file, key_file}`
  — an extra constructor parameter of `FastCacheStandaloneClient` and
  `FastCacheSmartClient`; the hostname is not verified.

## Verifying after startup

```bash
# gRPC ports (cache 50000, replica 52000, coordinator 51000):
openssl s_client -connect 127.0.0.1:50000 -CAfile /etc/hurricache/tls/ca.pem -alpn h2 </dev/null
# Expected: "New, TLSv1.3, Cipher is ...", "ALPN protocol: h2"

# With mTLS a client certificate is mandatory:
openssl s_client -connect 127.0.0.1:50000 -CAfile ca.pem -alpn h2 \
  -cert client-cert.pem -key client-key.pem </dev/null

# RESP port:
redis-cli -p 6379 --tls --cacert /etc/hurricache/tls/ca.pem PING   # PONG

# Plain connection to a TLS port must fail:
redis-cli -p 6379 PING   # Protocol error — expected on a TLS port
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Config error: 'tls.X': 'cert' and 'key' must be set together` | only one of the pair is set |
| `Config error: 'tls.X': file '...' is not readable or empty` | missing file/permissions, empty file |
| `TLS [X]: invalid identity key/cert pair` at startup | key does not match the certificate, or broken PEM |
| Cluster does not form after enabling TLS | TLS sections differ across nodes (TLS somewhere, plain elsewhere) |
| `remote error: tls: certificate required` / drop after handshake | server has `ca` set (mTLS), client has no certificate |
| Client complains about hostname/CN | disable hostname verification on the client — the server does not support it |

## Limitations

- TLS and plain traffic on the same port simultaneously are not supported.
- Certificate rotation requires a node restart (files are read at startup).
- TLS 1.2+ (TLS 1.3 is negotiated by default).
- The Prometheus metrics port remains plain — restrict it by network means
  (firewall, loopback bind).
