param([string]$Maven = 'mvn.cmd')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    Push-Location frontend
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Force src/main/resources/static | Out-Null
    Copy-Item -Path frontend/dist/* -Destination src/main/resources/static -Recurse -Force
    & $Maven -B package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
} finally { Pop-Location }
