<#
.SYNOPSIS
    Starts the entire PhotoPulse AI microservices stack in the correct dependency order.
.DESCRIPTION
    1. Checks Ollama and starts Docker infrastructure (PostgreSQL, Qdrant, Keycloak, Nginx).
    2. Starts Eureka Discovery Server and waits until port 8761 is ready.
    3. Starts API Gateway, Ingestion Service, and Assistant Service in dedicated PowerShell windows.
#>

$ErrorActionPreference = "Stop"
$RootDir = $PSScriptRoot

function Wait-ForPort {
    param (
        [string]$ServiceName,
        [int]$Port,
        [int]$TimeoutSeconds = 90
    )
    Write-Host "Waiting for $ServiceName on port $Port..." -ForegroundColor Yellow
    $elapsed = 0
    while ($elapsed -lt $TimeoutSeconds) {
        $connection = Test-NetConnection -ComputerName "localhost" -Port $Port -WarningAction SilentlyContinue
        if ($connection.TcpTestSucceeded) {
            Write-Host "$ServiceName is UP on port $Port!" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 3
        $elapsed += 3
    }
    throw "Timeout waiting for $ServiceName on port $Port after $TimeoutSeconds seconds."
}

Write-Host "=== Starting PhotoPulse AI Ecosystem ===" -ForegroundColor Cyan

# 1. Start Docker Infrastructure
Write-Host "`n[1/4] Starting Docker containers (PostgreSQL, Qdrant, Keycloak, Nginx)..." -ForegroundColor Cyan
Set-Location $RootDir
docker compose up -d

# Wait for critical infrastructure ports
Wait-ForPort -ServiceName "PostgreSQL" -Port 5433 -TimeoutSeconds 45
Wait-ForPort -ServiceName "Qdrant" -Port 6334 -TimeoutSeconds 45

# Check if Ollama is running
$ollamaCheck = Test-NetConnection -ComputerName "localhost" -Port 11434 -WarningAction SilentlyContinue
if (-not $ollamaCheck.TcpTestSucceeded) {
    Write-Host "Warning: Ollama is not detected on port 11434. Attempting to start Ollama..." -ForegroundColor DarkYellow
    Start-Process "ollama" -ArgumentList "serve" -WindowStyle Hidden
    Start-Sleep -Seconds 3
} else {
    Write-Host "Ollama is already running on port 11434." -ForegroundColor Green
}

# 2. Start Eureka Discovery Server
Write-Host "`n[2/4] Launching Discovery Server (Eureka)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\discovery-server'; `$Host.UI.RawUI.WindowTitle='Discovery Server (8761)'; .\mvnw.cmd spring-boot:run"
Wait-ForPort -ServiceName "Discovery Server (Eureka)" -Port 8761 -TimeoutSeconds 90

# Give Eureka 5 extra seconds to initialize its internal registry context
Start-Sleep -Seconds 5

# 3. Start API Gateway
Write-Host "`n[3/4] Launching API Gateway..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\api-gateway'; `$Host.UI.RawUI.WindowTitle='API Gateway (8080)'; .\mvnw.cmd spring-boot:run"
Wait-ForPort -ServiceName "API Gateway" -Port 8080 -TimeoutSeconds 90

# 4. Start Business Microservices
Write-Host "`n[4/4] Launching Ingestion Service and Assistant Service..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\ingestion-service'; `$Host.UI.RawUI.WindowTitle='Ingestion Service (8081)'; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\assistant-service'; `$Host.UI.RawUI.WindowTitle='Assistant Service (8082)'; .\mvnw.cmd spring-boot:run"

Write-Host "`n=== All launch commands issued! ===" -ForegroundColor Green
Write-Host "Eureka Dashboard : http://localhost:8761"
Write-Host "Qdrant Dashboard : http://localhost:6333/dashboard"
Write-Host "Nginx Gateway    : http://localhost"