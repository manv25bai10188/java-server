$ErrorActionPreference = "Stop"

& "$PSScriptRoot/build.ps1"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java --add-modules jdk.httpserver -cp out dev.barebones.server.Main

