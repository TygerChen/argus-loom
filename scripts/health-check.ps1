# 生产健康检查脚本：机器可读，退出码 0=健康 1=降级。
# 用法: .\scripts\health-check.ps1 -BaseUrl https://argus.example.com
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl
)

try {
    $resp = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
    foreach ($prop in $resp.components.PSObject.Properties) {
        Write-Host "$($prop.Name)=$($prop.Value.status)"
    }
    if ($resp.status -eq 'UP') { exit 0 } else { exit 1 }
} catch {
    Write-Host "health-check FAILED: $($_.Exception.Message)"
    exit 1
}
