# Bare-bones Java HTTPS + UDP server

A dependency-free Java 21 server that listens for HTTPS and UDP traffic in the same process.

## Run it

From PowerShell in the repository root:

```powershell
./scripts/generate-certificate.ps1
./scripts/run.ps1
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

UDP listens on port `9999` by default. UTF-8 `PING` receives `PONG`; any other datagram receives `ACK: <message>`. Datagram payloads are limited to 2048 bytes.

```powershell
$udp = [System.Net.Sockets.UdpClient]::new()
$udp.Client.ReceiveTimeout = 2000
$bytes = [Text.Encoding]::UTF8.GetBytes("PING")
$udp.Send($bytes, $bytes.Length, "localhost", 9999)
$remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
[Text.Encoding]::UTF8.GetString($udp.Receive([ref]$remote))
$udp.Dispose()
```

## Configuration

Set these environment variables before running:

| Variable | Default |
| --- | --- |
| `SERVER_BIND_ADDRESS` | `0.0.0.0` |
| `SERVER_HTTPS_PORT` | `8443` |
| `SERVER_UDP_PORT` | `9999` |
| `SERVER_KEYSTORE_PATH` | `certs/server.p12` |
| `SERVER_KEYSTORE_PASSWORD` | `changeit` |

For production, provide a trusted PKCS#12 certificate, set a strong password through the environment, and put authorization/rate limiting in front of application handlers as needed.
