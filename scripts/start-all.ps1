param(
    [int]$BackendPort = 10001,
    [int]$FrontendPort = 8081,
    [int]$EmbeddingPort = 11435,
    [string]$ElasticsearchHome = 'F:\DevelopmentTools\elasticsearch\elasticsearch-8.9.2',
    [string]$MinioExe = 'F:\DevelopmentTools\minio\minio.exe',
    [string]$MinioDataDir = 'F:\DevelopmentTools\minio-data'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

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

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing | Out-Null
            return $true
        }
        catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

# Elasticsearch
if (-not (Test-Port 9200)) {
    $esStart = Join-Path $ElasticsearchHome 'bin\elasticsearch.bat'
    if (-not (Test-Path $esStart)) {
        throw "Elasticsearch not found: $esStart"
    }
    Start-Process -FilePath $esStart -WorkingDirectory $ElasticsearchHome -WindowStyle Hidden | Out-Null
}
Write-Host 'Waiting for Elasticsearch...'
if (-not (Wait-Http 'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow')) {
    throw 'Elasticsearch did not become ready in time'
}
Write-Host 'Elasticsearch READY'

# MinIO
if (-not (Test-Port 9000)) {
    if (-not (Test-Path $MinioExe)) {
        throw "MinIO not found: $MinioExe"
    }
    Start-Process -FilePath $MinioExe -ArgumentList @('server', $MinioDataDir, '--address', ':9000', '--console-address', ':9001') -WindowStyle Hidden | Out-Null
}
Write-Host 'Waiting for MinIO...'
if (-not (Wait-Http 'http://127.0.0.1:9000/minio/health/live' 60)) {
    throw 'MinIO did not become ready in time'
}
Write-Host 'MinIO READY'

# 本地 LLM 网关（chat-only-gateway.py：仅代理 chat，embedding 已 JVM 内本地化；
# AGNES_API_KEY 来自上方 .env 加载——.env 值会覆盖系统环境变量里的旧值）
if (-not (Test-Port $EmbeddingPort)) {
    $gatewayScript = Join-Path $PSScriptRoot 'chat-only-gateway.py'
    $gatewayOut = Join-Path $logDir 'gateway.out.log'
    $gatewayErr = Join-Path $logDir 'gateway.err.log'
    Start-Process -FilePath 'python' -ArgumentList $gatewayScript `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden `
        -RedirectStandardOutput $gatewayOut -RedirectStandardError $gatewayErr | Out-Null
}
Write-Host 'Waiting for local LLM gateway...'
$embeddingDeadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $embeddingDeadline -and -not (Test-Port $EmbeddingPort)) {
    Start-Sleep -Seconds 2
}
if (-not (Test-Port $EmbeddingPort)) {
    throw 'Local LLM gateway did not become ready in time (检查 .env 是否配置 AGNES_API_KEY)'
}
Write-Host 'Local LLM gateway READY'

# Backend and frontend
& (Join-Path $PSScriptRoot 'start-argus-servers.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort
