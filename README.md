# Bare-bones Java HTTPS + UDP server

A dependency-free Java 21 server that listens for HTTPS and UDP traffic in the same process.

HTTPS and UDP requests are normalized into a shared, versioned message model before processing. Each message
contains a UUID, type, timestamp, protocol version, and immutable binary payload. The current external wire
formats remain intentionally simple; transport-independent encoding can be layered on top of this model.

Routing is registry-based. `HttpRouter` dispatches exact HTTP method/path pairs and supplies consistent `404` and
`405` responses. `MessageRouter` maps message types to handlers, and a custom router can be passed to
`MessageProcessor`, then into `DualProtocolServer`, without changing either network loop.

## Request correlation and logging

Every HTTPS response includes a server-generated `X-Request-ID`. Client-provided values are replaced so logs
cannot be spoofed with an untrusted correlation ID. Binary message requests retain their separate protocol UUID
as `message_id`; legacy UDP datagrams receive a generated request ID.

The server writes one structured completion event per HTTPS request or UDP datagram through the JDK
`System.Logger`. Events contain correlation IDs, remote address, operation, outcome, byte counts, and elapsed
microseconds. Payloads, key-store passwords, and certificate contents are never logged. Control characters,
quotes, and backslashes in values are escaped to prevent log injection.

Example event:

```text
event="https_request" timestamp="2026-01-01T00:00:00Z" request_id="..." remote="127.0.0.1:50000" method="GET" path="/health" status="200" request_bytes="0" response_bytes="61" duration_us="4000"
```

Applications embedding the server can pass a custom `ServerEventLogger` to the three-argument
`DualProtocolServer` constructor to forward these typed events to another destination.

## Metrics

`GET /metrics` returns a dependency-free, Prometheus-compatible text snapshot. It includes uptime, active and
total HTTPS/UDP work, request and response byte totals, cumulative execution time, HTTPS response statuses, UDP
outcomes, internal errors, and malformed binary-message counts.

```powershell
curl.exe -k https://localhost:8443/metrics
```

Metrics intentionally exclude request IDs, remote addresses, message IDs, and paths to avoid unbounded label
cardinality. The `/metrics` request is included in the accepted HTTPS request counter while its response status,
bytes, and duration appear on the following scrape because the snapshot is rendered before that request completes.

## Traffic controls

HTTPS and UDP use non-blocking concurrency permits and per-IP token buckets. HTTPS requests rejected by either
limit receive `429 Too Many Requests` with `Retry-After`. UDP datagrams are checked before a virtual-thread task is
created and receive either `ERROR: rate limited` or `ERROR: server busy`, so outstanding UDP work remains bounded.
HTTPS dispatch uses a zero-queue bounded virtual-thread executor; excess work reaches the admission handler on the
dispatcher thread instead of creating another virtual thread.

Rate buckets are separate for HTTPS and UDP, preventing traffic on one protocol from consuming the other
protocol's allowance. The number of tracked buckets is bounded, and fully refilled idle buckets are reclaimed.
Limits are held in memory and apply independently to each running server process.

## Run it

From PowerShell in the repository root:

```powershell
./scripts/generate-certificate.ps1
./scripts/run.ps1
```

Command-line options can override the defaults or environment variables:

```powershell
./scripts/run.ps1 --bind-address 127.0.0.1 --https-port 9443 --udp-port 9090
```

Show all options with:

```powershell
./scripts/run.ps1 --help
```

Run the dependency-free integration smoke test with:

```powershell
./scripts/test.ps1
```

The generated certificate is self-signed and intended only for local development. It is ignored by Git.

## Protocols

HTTPS listens on port `8443` by default:

```powershell
curl.exe -k https://localhost:8443/health
curl.exe -k -X POST -H "Content-Type: text/plain" --data "hello" https://localhost:8443/echo
```

The endpoints are:

- `GET /` — server identification
- `GET /health` — JSON health response
- `GET /metrics` — Prometheus-compatible server metrics
- `POST /echo` — returns the request body, up to 64 KiB
- `POST /message` — processes a binary `BJS1` message and returns a correlated binary response

UDP listens on port `9999` by default. UTF-8 `PING` receives `PONG`; any other datagram receives `ACK: <message>`. Datagram payloads are limited to 2048 bytes.

UDP also accepts the binary message format described below. Binary requests receive binary responses; the legacy
UTF-8 behavior remains available for simple clients.

```powershell
$udp = [System.Net.Sockets.UdpClient]::new()
$udp.Client.ReceiveTimeout = 2000
$bytes = [Text.Encoding]::UTF8.GetBytes("PING")
$udp.Send($bytes, $bytes.Length, "localhost", 9999)
$remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
[Text.Encoding]::UTF8.GetString($udp.Receive([ref]$remote))
$udp.Dispose()
```

## Binary message format

HTTPS and UDP share a dependency-free binary wire format. Multi-byte numbers are unsigned where applicable and
encoded in network byte order (big-endian). The fixed header is 41 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII magic `BJS1` |
| 4 | 4 | Protocol version (`1`) |
| 8 | 16 | UUID, most-significant bits first |
| 24 | 1 | Type: `1` PING, `2` DATA, `3` PONG, `4` ACK |
| 25 | 8 | Timestamp epoch seconds |
| 33 | 4 | Timestamp nanoseconds |
| 37 | 4 | Payload length |
| 41 | variable | Binary payload, up to 64 KiB |

Send encoded HTTPS messages to `POST /message` using the media type
`application/vnd.barebones.message`. `PING` produces a correlated `PONG`; `DATA` produces a correlated `ACK`
containing the same payload. Frames with invalid magic, unknown types, inconsistent lengths, or trailing data are
rejected. Since UDP datagrams are capped at 2048 bytes by this server, an encoded UDP message can carry at most
2007 payload bytes.

## Configuration

Configuration precedence is command-line option, environment variable, then default value:

| Command-line option | Environment variable | Default |
| --- | --- | --- |
| `--bind-address` | `SERVER_BIND_ADDRESS` | `0.0.0.0` |
| `--https-port` | `SERVER_HTTPS_PORT` | `8443` |
| `--udp-port` | `SERVER_UDP_PORT` | `9999` |
| `--keystore` | `SERVER_KEYSTORE_PATH` | `certs/server.p12` |
| `--keystore-password` | `SERVER_KEYSTORE_PASSWORD` | `changeit` |
| `--max-concurrent-https` | `SERVER_MAX_CONCURRENT_HTTPS` | `256` |
| `--max-concurrent-udp` | `SERVER_MAX_CONCURRENT_UDP` | `256` |
| `--rate-limit-capacity` | `SERVER_RATE_LIMIT_CAPACITY` | `100` |
| `--rate-limit-refill-per-second` | `SERVER_RATE_LIMIT_REFILL_PER_SECOND` | `50` |
| `--rate-limit-max-clients` | `SERVER_RATE_LIMIT_MAX_CLIENTS` | `10000` |

Both `--option value` and `--option=value` forms are supported. Prefer
`SERVER_KEYSTORE_PASSWORD` for secrets because command-line arguments may be visible to other local processes.

For production, provide a trusted PKCS#12 certificate and set a strong password through the environment.
