param(
    [int]$Port = 10001
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $projectRoot 'backend'
$logDir = Join-Path $backendDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$existing = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($existing) {
    $existing | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}

$env:JAVA_HOME = 'C:\Users\yuanchentai\.jdks\corretto-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$backendOut = Join-Path $logDir 'backend-run.out.log'
$backendErr = Join-Path $logDir 'backend-run.err.log'
# 加载根目录 .env 中的本地环境变量（数据库密码等，不入仓库）
$envFile = Join-Path $projectRoot '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $parts = $line -split '=', 2
            if ($parts.Length -eq 2) {
                Set-Item -Path "Env:$($parts[0].Trim())" -Value $parts[1].Trim()
            }
        }
    }
}

Start-Process -FilePath (Join-Path $backendDir 'mvnw.cmd') -ArgumentList '-q','spring-boot:run' `
    -WorkingDirectory $backendDir -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr `
    -WindowStyle Hidden | Out-Null

$deadline = (Get-Date).AddSeconds(300)
$up = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode) {
            $up = $true
            break
        }
    } catch {
    }
}
if (-not $up) {
    Write-Host 'BACKEND_NOT_UP'
    if (Test-Path $backendErr) { Get-Content $backendErr -Tail 60 }
    if (Test-Path $backendOut) { Get-Content $backendOut -Tail 100 }
    exit 1
}
Write-Host "BACKEND_UP http://localhost:$Port"
