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

foreach ($testClass in @(
    "dev.barebones.server.ServerConfigTest",
    "dev.barebones.server.MessageProcessorTest",
    "dev.barebones.server.MessageCodecTest",
    "dev.barebones.server.HttpRouterTest",
    "dev.barebones.server.MessageRouterTest",
    "dev.barebones.server.ServerSmokeTest"
)) {
    & java --add-modules jdk.httpserver -cp out $testClass
    if ($LASTEXITCODE -ne 0) {
        throw "$testClass failed with exit code $LASTEXITCODE"
    }
}
