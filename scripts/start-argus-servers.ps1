param(
    [int]$BackendPort = 10001,
    [int]$FrontendPort = 8081
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $projectRoot 'backend'
$frontendDir = Join-Path $projectRoot 'frontend'
$logDir = Join-Path $backendDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$env:JAVA_HOME = 'C:\Users\yuanchentai\.jdks\corretto-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

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

$backendOut = Join-Path $logDir 'backend-run.out.log'$backendErr = Join-Path $logDir 'backend-run.err.log'
$frontendOut = Join-Path $logDir 'frontend-run.out.log'
$frontendErr = Join-Path $logDir 'frontend-run.err.log'

Start-Process -FilePath (Join-Path $backendDir 'mvnw.cmd') -ArgumentList '-q','spring-boot:run' `
    -WorkingDirectory $backendDir -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr `
    -WindowStyle Hidden | Out-Null

$deadline = (Get-Date).AddSeconds(300)
$backendUp = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$BackendPort/actuator/health" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode) {
            $backendUp = $true
            break
        }
    } catch {
    }
}

if (-not $backendUp) {
    Write-Host 'BACKEND_NOT_UP'
    if (Test-Path $backendErr) { Get-Content $backendErr -Tail 60 }
    if (Test-Path $backendOut) { Get-Content $backendOut -Tail 80 }
    exit 1
}
Write-Host "BACKEND_UP http://localhost:$BackendPort"

Start-Process -FilePath 'npm.cmd' -ArgumentList 'run','dev','--','--port',"$FrontendPort",'--strictPort' `
    -WorkingDirectory $frontendDir -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr `
    -WindowStyle Hidden | Out-Null

$frontendDeadline = (Get-Date).AddSeconds(90)
$frontendUp = $false
while ((Get-Date) -lt $frontendDeadline) {
    Start-Sleep -Seconds 3
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$FrontendPort" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode) {
            $frontendUp = $true
            break
        }
    } catch {
    }
}
if (-not $frontendUp) {
    Write-Host 'FRONTEND_NOT_READY'
    if (Test-Path $frontendErr) { Get-Content $frontendErr -Tail 40 }
    if (Test-Path $frontendOut) { Get-Content $frontendOut -Tail 40 }
    exit 1
}
Write-Host "FRONTEND_UP http://localhost:$FrontendPort"
