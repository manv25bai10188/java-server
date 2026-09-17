$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Path "out" -Force | Out-Null
$sources = Get-ChildItem -Path "src/main/java" -Recurse -Filter "*.java" | ForEach-Object FullName

& javac --release 21 --add-modules jdk.httpserver -d out $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Write-Host "Compiled server classes into out/"

