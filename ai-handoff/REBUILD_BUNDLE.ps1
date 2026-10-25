param(
    [string]$OutputRoot = "F:\ChatGPT",
    [string[]]$KnownSecrets = @()
)

$ErrorActionPreference = "Stop"

function Assert-SafeChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Parent,
        [Parameter(Mandatory = $true)][string]$Child
    )

    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    $childFull = [System.IO.Path]::GetFullPath($Child)
    if (-not $childFull.StartsWith($parentFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe path. Parent=$parentFull Child=$childFull"
    }
}

function Copy-Tree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [string[]]$ExcludeDirs = @(),
        [string[]]$ExcludeFiles = @()
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        return
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null

    $robocopyArgs = @(
        $Source,
        $Destination,
        "/E",
        "/COPY:DAT",
        "/DCOPY:DAT",
        "/R:2",
        "/W:1",
        "/NFL",
        "/NDL",
        "/NJH",
        "/NJS",
        "/NP"
    )

    if ($ExcludeDirs.Count -gt 0) {
        $robocopyArgs += "/XD"
        $robocopyArgs += $ExcludeDirs
    }
    if ($ExcludeFiles.Count -gt 0) {
        $robocopyArgs += "/XF"
        $robocopyArgs += $ExcludeFiles
    }

    & robocopy @robocopyArgs | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed with exit code $LASTEXITCODE for $Source"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = [System.IO.Path]::GetFullPath($repoRoot)
$outputRootFull = [System.IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path $outputRootFull | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$bundleName = "Argus-Loom-AI-Handoff-$stamp"
$bundleStagePath = Join-Path $outputRootFull $bundleName
$zipPath = Join-Path $outputRootFull "$bundleName.zip"

Assert-SafeChildPath -Parent $outputRootFull -Child $bundleStagePath
if (Test-Path -LiteralPath $bundleStagePath) {
    throw "Stage directory already exists: $bundleStagePath"
}
if (Test-Path -LiteralPath $zipPath) {
    throw "Zip already exists: $zipPath"
}

New-Item -ItemType Directory -Path $bundleStagePath | Out-Null

$rootFiles = @(
    "README.md",
    "AGENTS.md",
    "CLAUDE.md",
    "MEMORY.md",
    ".env.example",
    ".gitignore",
    ".zcodeignore"
)

foreach ($file in $rootFiles) {
    $sourceFile = Join-Path $repoRoot $file
    if (Test-Path -LiteralPath $sourceFile) {
        Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $bundleStagePath $file)
    }
}

Copy-Tree `
    -Source (Join-Path $repoRoot "ai-handoff") `
    -Destination (Join-Path $bundleStagePath "ai-handoff") `
    -ExcludeDirs @("__pycache__") `
    -ExcludeFiles @("*.log", "*.tmp")

Copy-Tree `
    -Source (Join-Path $repoRoot "backend") `
    -Destination (Join-Path $bundleStagePath "backend") `
    -ExcludeDirs @(".idea", "target", "logs", "upload", "tmp", "__pycache__") `
    -ExcludeFiles @("*.log", "*.tmp", "application-local.yml")

Copy-Tree `
    -Source (Join-Path $repoRoot "frontend") `
    -Destination (Join-Path $bundleStagePath "frontend") `
    -ExcludeDirs @("node_modules", "dist", "dist-ssr", "coverage", "__screenshots__") `
    -ExcludeFiles @("*.log", ".eslintcache")

Copy-Tree `
    -Source (Join-Path $repoRoot "docs") `
    -Destination (Join-Path $bundleStagePath "docs") `
    -ExcludeDirs @("__pycache__") `
    -ExcludeFiles @("*.log")

Copy-Tree `
    -Source (Join-Path $repoRoot "scripts") `
    -Destination (Join-Path $bundleStagePath "scripts") `
    -ExcludeDirs @("__pycache__") `
    -ExcludeFiles @("*.log", "*.tmp")

Copy-Tree `
    -Source (Join-Path $repoRoot "sql") `
    -Destination (Join-Path $bundleStagePath "sql") `
    -ExcludeDirs @("__pycache__", "backups") `
    -ExcludeFiles @("*.log", "*.dump", "*.sql.gz", "*.zip")

Copy-Tree `
    -Source (Join-Path $repoRoot ".zcode") `
    -Destination (Join-Path $bundleStagePath ".zcode") `
    -ExcludeDirs @("__pycache__") `
    -ExcludeFiles @("*.log")

# robocopy 对深层同名目录的排除取决于调用方式；这里再做一次有边界校验的兜底清理。
$forbiddenDirNames = @("node_modules", "target", "dist", "dist-ssr", "coverage", "upload", "logs", ".idea", ".git")
$forbiddenDirs = Get-ChildItem -LiteralPath $bundleStagePath -Directory -Recurse -Force |
    Where-Object { $forbiddenDirNames -contains $_.Name } |
    Sort-Object { $_.FullName.Length } -Descending
foreach ($dir in $forbiddenDirs) {
    Assert-SafeChildPath -Parent $bundleStagePath -Child $dir.FullName
    Remove-Item -LiteralPath $dir.FullName -Recurse -Force
}

$memorySources = Join-Path $bundleStagePath "ai-handoff\memory-sources"
New-Item -ItemType Directory -Force -Path $memorySources | Out-Null

$rootMemoryPath = Join-Path $repoRoot "MEMORY.md"
if (Test-Path -LiteralPath $rootMemoryPath) {
    Copy-Item -LiteralPath $rootMemoryPath -Destination (Join-Path $memorySources "MEMORY.md")
}

$ragMemoryPath = Join-Path $repoRoot "docs\MEMORY-知识库问答.md"
if (Test-Path -LiteralPath $ragMemoryPath) {
    Copy-Item -LiteralPath $ragMemoryPath -Destination (Join-Path $memorySources "MEMORY-知识库问答.md")
}

# 打包副本只做配置脱敏，不修改用户原仓库。
$devConfigStagePath = $bundleStagePath + '\backend\src\main\resources\application-dev.yml'
if (Test-Path -LiteralPath $devConfigStagePath) {
    $configText = Get-Content -LiteralPath $devConfigStagePath -Raw
    # 仅替换数据库默认密码，保留 ${POSTGRES_PASSWORD:...} 这种环境变量入口。
    # 不能笼统替换所有 password 行，否则会破坏管理员密码、MinIO 等其它配置。
    $configText = $configText -replace '(?m)^(\s*password:\s*\$\{POSTGRES_PASSWORD:)[^}]+\}(\s*(?:#.*)?)$', '${1}change-me}${2}'
    Set-Content -LiteralPath $devConfigStagePath -Value $configText -Encoding UTF8
}

# 历史测试和迁移脚本里有本地开发密码。只在交接包副本中替换，避免把本机凭据扩散给其它 AI。
$stagePgVectorTest = "$bundleStagePath\backend\src\test\java\com\argus\rag\embedding\PgVectorEndToEndTest.java"
if (Test-Path -LiteralPath $stagePgVectorTest) {
    $testText = Get-Content -LiteralPath $stagePgVectorTest -Raw
    $testText = $testText -replace '(?m)^(\s*private static final String PASSWORD =\s*).*;\s*$', '${1}System.getenv().getOrDefault("POSTGRES_PASSWORD", "change-me");'
    Set-Content -LiteralPath $stagePgVectorTest -Value $testText -Encoding UTF8
}

$stageMigrationScript = "$bundleStagePath\sql\migrate_mysql_to_pg.py"
if (Test-Path -LiteralPath $stageMigrationScript) {
    $migrationText = Get-Content -LiteralPath $stageMigrationScript -Raw
    $migrationText = $migrationText -replace '(--mysql-pass\s+)\S+', '${1}change-me'
    $migrationText = $migrationText -replace '(--pg-pass\s+)\S+', '${1}change-me'
    $migrationText = $migrationText -replace '(?m)^(\s*parser\.add_argument\("--mysql-password",\s*default=)"[^"]*"', '${1}os.getenv("MYSQL_PASSWORD", "change-me")'
    $migrationText = $migrationText -replace '(?m)^(\s*parser\.add_argument\("--pg-password",\s*default=)"[^"]*"', '${1}os.getenv("POSTGRES_PASSWORD", "change-me")'
    Set-Content -LiteralPath $stageMigrationScript -Value $migrationText -Encoding UTF8
}

# 本地网关脚本曾把 API Key 写进回退值。这里只替换变量后的密钥字面量。
foreach ($scriptRelativePath in @("scripts\chat-only-gateway.py", "scripts\local-embedding-server.py")) {
    $stageScriptPath = "$bundleStagePath\$scriptRelativePath"
    if (Test-Path -LiteralPath $stageScriptPath) {
        $scriptText = Get-Content -LiteralPath $stageScriptPath -Raw
        $scriptText = $scriptText -replace '"sk-[A-Za-z0-9_-]{16,}"', '"change-me"'
        Set-Content -LiteralPath $stageScriptPath -Value $scriptText -Encoding UTF8
    }
}

$stageDevAdminInitializer = "$bundleStagePath\backend\src\main\java\com\argus\rag\auth\config\DevAdminInitializer.java"
if (Test-Path -LiteralPath $stageDevAdminInitializer) {
    $initializerText = Get-Content -LiteralPath $stageDevAdminInitializer -Raw
    $initializerText = $initializerText -replace '(\$\{argus\.rag\.dev-admin\.password:)[^}]+\}', '${1}change-me}'
    Set-Content -LiteralPath $stageDevAdminInitializer -Value $initializerText -Encoding UTF8
}

Push-Location $repoRoot
try {
    $history = git log -30 --date=iso --pretty=format:"%h`t%ad`t%s"
    $status = git status --short --branch
    $historyDoc = @(
        "# Git History Snapshot",
        "",
        "> Full .git history is intentionally not included in this sanitized AI handoff.",
        "> It may contain historical configuration values that should not be redistributed.",
        "",
        "## Status At Packaging",
        "",
        '```text',
        $status,
        '```',
        "",
        "## Recent Commits",
        "",
        '```text',
        $history,
        '```'
    )
    Set-Content -LiteralPath (Join-Path $bundleStagePath "GIT_HISTORY.md") -Value $historyDoc -Encoding UTF8
}
finally {
    Pop-Location
}

$sanitizationNotice = @(
    "# Sanitization Notice",
    "",
    "This bundle is a sanitized working-tree snapshot for AI handoff.",
    "The original repository was not changed.",
    "",
    "Sanitized in this bundle only:",
    "",
    '1. `backend/src/main/resources/application-dev.yml`: the local PostgreSQL default password was replaced with `change-me`.',
    '2. `backend/src/test/java/com/argus/rag/embedding/PgVectorEndToEndTest.java`: the hard-coded password now reads `POSTGRES_PASSWORD` with a safe fallback.',
    '3. `sql/migrate_mysql_to_pg.py`: hard-coded local migration passwords were replaced with `change-me`.',
    '4. `scripts/chat-only-gateway.py` and `scripts/local-embedding-server.py`: hard-coded API key fallbacks were replaced with `change-me`.',
    '5. `backend/src/main/java/com/argus/rag/auth/config/DevAdminInitializer.java`: the default development administrator password was replaced with `change-me`.',
    "",
    'Not included: `.env`, `.git`, IDE state, runtime uploads, logs, build outputs, `node_modules`, database backups and `application-local.yml`.',
    "",
    'Before running the project, create your own `.env` from `.env.example`.'
)
Set-Content -LiteralPath (Join-Path $bundleStagePath "SANITIZATION_NOTICE.md") -Value $sanitizationNotice -Encoding UTF8

$files = Get-ChildItem -LiteralPath $bundleStagePath -Recurse -File | Sort-Object FullName
$indexLines = @("relative_path`tbytes")
foreach ($file in $files) {
    $relative = $file.FullName.Substring($bundleStagePath.Length).TrimStart('\')
    $indexLines += "$relative`t$($file.Length)"
}
Set-Content -LiteralPath (Join-Path $bundleStagePath "FILE_INDEX.tsv") -Value $indexLines -Encoding UTF8

$hashLines = foreach ($file in $files) {
    $relative = $file.FullName.Substring($bundleStagePath.Length).TrimStart('\')
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $relative"
}
Set-Content -LiteralPath (Join-Path $bundleStagePath "SHA256SUMS.txt") -Value $hashLines -Encoding UTF8

$packageInfo = [ordered]@{
    generated_at = (Get-Date).ToString("o")
    source_root = $repoRoot
    bundle_name = $bundleName
    file_count_before_manifest = $files.Count
    excluded = @(
        ".env",
        ".git",
        ".idea",
        "backend/target",
        "backend/upload",
        "backend/logs",
        "backend/src/main/resources/application-local.yml",
        "frontend/node_modules",
        "frontend/dist",
        "logs",
        "sql/backups"
    )
    note = "This is a sanitized working-tree snapshot. The original repository was not modified."
}
$packageInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $bundleStagePath "PACKAGE_INFO.json") -Encoding UTF8

# 最后的脱敏防线。已知密钥由调用方通过参数或环境变量传入，
# 不把本机密码硬编码进这个可被继续分发的脚本。
$secretsToScan = @($KnownSecrets)
if (-not [string]::IsNullOrWhiteSpace($env:ARGUS_KNOWN_SECRETS)) {
    $secretsToScan += $env:ARGUS_KNOWN_SECRETS -split '[,;\s]+'
}
foreach ($secret in ($secretsToScan | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)) {
    $leakHits = & rg -n -i -F --hidden `
        --glob '!.git/**' `
        --glob '!frontend/node_modules/**' `
        --glob '!backend/target/**' `
        --glob '!SHA256SUMS.txt' `
        --glob '!FILE_INDEX.tsv' `
        -- "$secret" `
        $bundleStagePath 2>$null
    if ($LASTEXITCODE -eq 0 -and $leakHits) {
        throw "Potential secret found in staged package: $leakHits"
    }
}

# 与具体值无关的通用凭证特征扫描。
$credentialHits = & rg -n -i --pcre2 --hidden `
    --glob '!.git/**' `
    --glob '!frontend/node_modules/**' `
    --glob '!backend/target/**' `
    --glob '!SHA256SUMS.txt' `
    --glob '!FILE_INDEX.tsv' `
    -e 'sk-[A-Za-z0-9_-]{20,}' `
    -e 'AKIA[0-9A-Z]{16}' `
    -e 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' `
    $bundleStagePath 2>$null
if ($LASTEXITCODE -eq 0 -and $credentialHits) {
    throw "Potential credential found in staged package: $credentialHits"
}

Compress-Archive -LiteralPath $bundleStagePath -DestinationPath $zipPath -CompressionLevel Optimal

$zipInfo = Get-Item -LiteralPath $zipPath
Write-Output "STAGE=$bundleStagePath"
Write-Output "ZIP=$zipPath"
Write-Output "ZIP_MB=$([math]::Round($zipInfo.Length / 1MB, 2))"
Write-Output "FILES=$($files.Count)"
