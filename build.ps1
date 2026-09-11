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
    # 前端产物带内容哈希，必须先清空旧目录，否则历代 bundle 会一起打进 jar。
    if (Test-Path src/main/resources/static) { Remove-Item -Recurse -Force src/main/resources/static }
    New-Item -ItemType Directory -Force src/main/resources/static | Out-Null
    Copy-Item -Path frontend/dist/* -Destination src/main/resources/static -Recurse -Force
    # clean 是必需的：Maven 不会删除 target/classes 里已被移除的资源，
    # 增量构建会把上一代前端 bundle 一起打进 jar。
    & $Maven -B clean package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
} finally { Pop-Location }
