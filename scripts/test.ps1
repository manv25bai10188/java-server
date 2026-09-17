$ErrorActionPreference = "Stop"

if (-not (Test-Path "certs/server.p12")) {
    & "$PSScriptRoot/generate-certificate.ps1"
}

& "$PSScriptRoot/build.ps1"

$testSources = Get-ChildItem -Path "src/test/java" -Recurse -Filter "*.java" | ForEach-Object FullName
& javac --release 21 --add-modules jdk.httpserver -cp out -d out $testSources
if ($LASTEXITCODE -ne 0) {
    throw "test compilation failed with exit code $LASTEXITCODE"
}

& java --add-modules jdk.httpserver -cp out dev.barebones.server.ServerSmokeTest
if ($LASTEXITCODE -ne 0) {
    throw "smoke tests failed with exit code $LASTEXITCODE"
}

