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
            Write-Host "Stopping microservice process (PID: $procId) on port $port..." -ForegroundColor Yellow
            Stop-Process -Id $procId -Force
        }
    }
}

# 2. Stop Ollama background processes
Write-Host "Stopping Ollama server..." -ForegroundColor Yellow
Get-Process -Name "ollama", "ollama app" | Stop-Process -Force

# 3. Stop Docker containers (leave Docker Desktop engine intact)
Write-Host "Stopping Docker containers..." -ForegroundColor Yellow
Set-Location $RootDir
docker compose down

Write-Host "=== All services, Ollama, and containers stopped! ===" -ForegroundColor Green