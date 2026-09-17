# Bare-bones Java HTTPS + UDP server

A dependency-free Java 21 server that listens for HTTPS and UDP traffic in the same process.

HTTPS and UDP requests are normalized into a shared, versioned message model before processing. Each message
contains a UUID, type, timestamp, protocol version, and immutable binary payload. The current external wire
formats remain intentionally simple; transport-independent encoding can be layered on top of this model.

Routing is registry-based. `HttpRouter` dispatches exact HTTP method/path pairs and supplies consistent `404` and
`405` responses. `MessageRouter` maps message types to handlers, and a custom router can be passed to
`MessageProcessor`, then into `DualProtocolServer`, without changing either network loop.

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

Both `--option value` and `--option=value` forms are supported. Prefer
`SERVER_KEYSTORE_PASSWORD` for secrets because command-line arguments may be visible to other local processes.

For production, provide a trusted PKCS#12 certificate, set a strong password through the environment, and put authorization/rate limiting in front of application handlers as needed.
