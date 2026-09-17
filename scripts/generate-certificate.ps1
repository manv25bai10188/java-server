param(
    [string]$KeyStorePath = "certs/server.p12",
    [string]$Password = "changeit"
)

$ErrorActionPreference = "Stop"
$parent = Split-Path -Parent $KeyStorePath
if ($parent) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

& keytool -genkeypair `
    -alias server `
    -keyalg RSA `
    -keysize 2048 `
    -validity 365 `
    -storetype PKCS12 `
    -keystore $KeyStorePath `
    -storepass $Password `
    -keypass $Password `
    -dname "CN=localhost, OU=Development, O=Bare Bones Server, L=Local, ST=Local, C=US" `
    -ext "SAN=dns:localhost,ip:127.0.0.1"

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

Write-Host "Created development certificate at $KeyStorePath"

