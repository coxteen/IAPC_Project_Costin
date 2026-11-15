$ErrorActionPreference = "SilentlyContinue"
$RootDir = $PSScriptRoot

Write-Host "=== Stopping PhotoPulse AI Ecosystem ===" -ForegroundColor Cyan

# 1. Kill processes listening on microservice ports
$ports = @(8761, 8080, 8081, 8082)
foreach ($port in $ports) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen
    foreach ($conn in $connections) {
        $procId = $conn.OwningProcess
        if ($procId -gt 0) {
            Write-Host "Stopping process (PID: $procId) on port $port..." -ForegroundColor Yellow
            Stop-Process -Id $procId -Force
        }
    }
}

# 2. Stop Docker containers
Write-Host "Stopping Docker containers..." -ForegroundColor Yellow
Set-Location $RootDir
docker compose down

Write-Host "=== All services and containers stopped! ===" -ForegroundColor Green