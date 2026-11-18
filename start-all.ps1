$ErrorActionPreference = "Continue"
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
        $tcp = New-Object System.Net.Sockets.TcpClient
        try {
            $connect = $tcp.BeginConnect("127.0.0.1", $Port, $null, $null)
            $wait = $connect.AsyncWaitHandle.WaitOne(500, $false)
            if ($wait -and $tcp.Connected) {
                $tcp.Close()
                Write-Host "$ServiceName is UP on port $Port!" -ForegroundColor Green
                return $true
            }
        } catch {}
        $tcp.Close()
        Start-Sleep -Seconds 2
        $elapsed += 2
    }
    Write-Host "WARNING: Timeout waiting for $ServiceName on port $Port." -ForegroundColor Red
    return $false
}

Write-Host "=== Starting PhotoPulse AI Ecosystem ===" -ForegroundColor Cyan

# 0. Ensure Docker Desktop is running
Write-Host "`n[0/5] Checking Docker Desktop..." -ForegroundColor Cyan
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dockerPath = "$Env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $dockerPath) {
        Write-Host "Starting Docker Desktop..." -ForegroundColor Yellow
        Start-Process -FilePath $dockerPath
    } else {
        Write-Host "Docker Desktop executable not found at default path. Please start it manually." -ForegroundColor Red
    }

    $dockerReady = $false
    for ($i = 0; $i -lt 45; $i++) {
        Start-Sleep -Seconds 2
        docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            $dockerReady = $true
            break
        }
    }
    if (-not $dockerReady) {
        Write-Host "ERROR: Docker Engine did not start in time. Exiting." -ForegroundColor Red
        exit 1
    }
}
Write-Host "Docker Engine is running!" -ForegroundColor Green

# 1. Start Docker Infrastructure
Write-Host "`n[1/5] Starting Docker containers (PostgreSQL, Qdrant, Keycloak, Nginx)..." -ForegroundColor Cyan
Set-Location $RootDir
docker compose up -d

Wait-ForPort -ServiceName "PostgreSQL" -Port 5433 -TimeoutSeconds 45 | Out-Null
Wait-ForPort -ServiceName "Qdrant" -Port 6334 -TimeoutSeconds 45 | Out-Null

# 2. Ensure Ollama is running
Write-Host "`n[2/5] Checking Ollama LLM server..." -ForegroundColor Cyan
$ollamaUp = Wait-ForPort -ServiceName "Ollama" -Port 11434 -TimeoutSeconds 4
if (-not $ollamaUp) {
    Write-Host "Launching Ollama..." -ForegroundColor Yellow
    $ollamaApp = "$Env:LOCALAPPDATA\Programs\Ollama\ollama app.exe"
    if (Test-Path $ollamaApp) {
        Start-Process -FilePath $ollamaApp
    } else {
        Start-Process "ollama" -ArgumentList "serve" -WindowStyle Hidden
    }
    Wait-ForPort -ServiceName "Ollama" -Port 11434 -TimeoutSeconds 30 | Out-Null
}

# 3. Start Eureka Discovery Server
Write-Host "`n[3/5] Launching Discovery Server (Eureka)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\discovery-server'; `$Host.UI.RawUI.WindowTitle='Discovery Server (8761)'; .\mvnw.cmd spring-boot:run"
Wait-ForPort -ServiceName "Discovery Server (Eureka)" -Port 8761 -TimeoutSeconds 90 | Out-Null
Start-Sleep -Seconds 5

# 4. Start API Gateway
Write-Host "`n[4/5] Launching API Gateway..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\api-gateway'; `$Host.UI.RawUI.WindowTitle='API Gateway (8080)'; .\mvnw.cmd spring-boot:run"
Wait-ForPort -ServiceName "API Gateway" -Port 8080 -TimeoutSeconds 90 | Out-Null

# 5. Start Business Microservices and wait for both to be UP
Write-Host "`n[5/5] Launching Ingestion Service and Assistant Service..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\ingestion-service'; `$Host.UI.RawUI.WindowTitle='Ingestion Service (8081)'; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$RootDir\assistant-service'; `$Host.UI.RawUI.WindowTitle='Assistant Service (8082)'; .\mvnw.cmd spring-boot:run"

Wait-ForPort -ServiceName "Ingestion Service" -Port 8081 -TimeoutSeconds 90 | Out-Null
Wait-ForPort -ServiceName "Assistant Service" -Port 8082 -TimeoutSeconds 90 | Out-Null

Write-Host "`nWaiting 10 seconds for Eureka & Gateway route sync..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host "`n=== PhotoPulse AI Ecosystem is READY! ===" -ForegroundColor Green
Write-Host "Eureka Dashboard : http://localhost:8761"
Write-Host "Qdrant Dashboard : http://localhost:6333/dashboard"
Write-Host "Nginx Gateway    : http://localhost"