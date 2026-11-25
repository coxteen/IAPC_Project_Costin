# PhotoPulse AI

**Enterprise Camera Manual Assistant**

PhotoPulse AI is a distributed microservices platform for ingesting, indexing, and querying camera instruction manuals. It combines hierarchical document chunking, advanced Retrieval-Augmented Generation (RAG), local AI models, multi-tenant metadata filtering, OAuth2/OIDC authentication, and role-based access control.

## System Architecture

```text
                        ┌─────────────────────────┐
                        │     Client / cURL /     │
                        │       PowerShell        │
                        └────────────┬────────────┘
                                     │ HTTP (:80)
                                     ▼
                        ┌─────────────────────────┐
                        │   Nginx Reverse Proxy   │
                        └────────────┬────────────┘
                                     │ (:8080)
                                     ▼
┌─────────────────┐     ┌─────────────────────────┐     ┌─────────────────────────┐
│    Keycloak     │◄────┤       API Gateway       │────►│    Discovery Server     │
│  OAuth2 / OIDC  │     │ (Spring Cloud Gateway + │     │    (Netflix Eureka)     │
│     (:8180)     │     │ OAuth2 Resource Server) │     │         (:8761)         │
└─────────────────┘     └──────┬───────────┬──────┘     └─────────────────────────┘
                               │           │
               ┌───────────────┘           └───────────────┐
               ▼ (:8081)                                   ▼ (:8082)
┌──────────────────────────────┐            ┌──────────────────────────────┐
│      Ingestion Service       │            │      Assistant Service       │
│  - Async PDF/DOCX/TXT        │            │  - Multi-Query Expansion     │
│  - Parent-Child Chunking     │            │  - Tenant/Model Filtering    │
│  - Multi-Tenant Metadata     │            │  - Gap Analysis and Audit    │
└───────┬──────────────┬───────┘            └───────┬──────────────┬───────┘
        │              │                            │              │
        ▼              └──────────┐      ┌──────────┘              ▼
┌───────────────┐          ┌────────────────────┐          ┌───────────────┐
│  PostgreSQL   │          │   Qdrant Vector    │          │    Ollama     │
│   Database    │          │     Database       │          │   Local LLM   │
│    (:5433)    │          │  (:6333 / :6334)   │          │   (:11434)    │
└───────────────┘          └────────────────────┘          └───────────────┘
```

## Technology Stack

| Area | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.1.2 |
| Microservices | Spring Cloud Gateway, Netflix Eureka |
| AI and RAG | Spring AI 2.0.0-M1, Ollama |
| Document parsing | Apache Tika |
| Vector storage | Qdrant over gRPC |
| Relational storage | PostgreSQL 16 |
| Authentication | Keycloak, OAuth2/OpenID Connect, JWT |
| Authorization | Spring Security WebFlux RBAC |
| Infrastructure | Docker Compose, Nginx |

## Core Features

### Document Ingestion

- Accepts PDF, DOCX, and TXT camera manuals.
- Uses virtual threads for asynchronous processing.
- Applies parent-child chunking:
  - Parent chunks contain approximately 1000 tokens for rich context.
  - Child chunks contain approximately 200 tokens for precise similarity search.
- Stores ingestion status and audit information in PostgreSQL.
- Adds tenant, camera model, brand, and page metadata to each chunk.

### Advanced RAG

- Expands each question into three semantic query variations.
- Searches with strict tenant and camera-model filters.
- Uses `nomic-embed-text` embeddings with 768 dimensions.
- Uses `llama3.2` for response generation and query expansion.
- Stops generation when no matching chunks pass the similarity threshold.
- Returns source file names, page numbers, and supporting text snippets.
- Stores assistant interactions in the PostgreSQL `audit_logs` table.
- Supports gap analysis against a target hardware or feature checklist.

### Enterprise Security

- Nginx exposes the public HTTP entry point on port 80.
- The API gateway verifies Keycloak JWT signatures and issuer information.
- `ROLE_COMPLIANCE_OFFICER` or `ROLE_ADMIN` is required to upload manuals.
- Any other API request requires an authenticated token.

## Services and Ports

| Component | URL or port |
|---|---|
| Public Nginx gateway | `http://localhost` |
| API gateway | `http://localhost:8080` |
| Ingestion service | `http://localhost:8081` |
| Assistant service | `http://localhost:8082` |
| Eureka | `http://localhost:8761` |
| Keycloak | `http://localhost:8180` |
| PostgreSQL | `localhost:5433` |
| Qdrant REST and dashboard | `http://localhost:6333` |
| Qdrant gRPC | `localhost:6334` |
| Ollama | `http://localhost:11434` |

## Prerequisites

- Java JDK 25
- Docker Desktop
- Ollama installed on the host
- PowerShell

Pull the required models:

```powershell
ollama pull llama3.2
ollama pull nomic-embed-text
```

## Quick Start

From the project root, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-all.ps1
```

The startup script performs these operations:

1. Starts Docker Desktop when necessary.
2. Starts PostgreSQL, Qdrant, Keycloak, and Nginx.
3. Starts or launches Ollama.
4. Starts Eureka.
5. Starts the API gateway.
6. Starts the ingestion and assistant services.

After startup, open:

- Nginx entry point: `http://localhost`
- Eureka: `http://localhost:8761`
- Qdrant dashboard: `http://localhost:6333/dashboard`
- Keycloak: `http://localhost:8180`

To stop the ecosystem:

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-all.ps1
```

This stops the Java microservices, Ollama, and Docker Compose containers. Docker Desktop itself remains running.

## Keycloak Configuration

The API gateway expects the following OIDC configuration:

- Realm: `photopulse`
- Issuer: `http://localhost:8180/realms/photopulse`
- Client ID: `photopulse-client`

Create the realm, client, users, and roles in the Keycloak admin console before requesting tokens. The example below assumes a user named `officer` with password `parola123` and the `ROLE_COMPLIANCE_OFFICER` realm role.

The Keycloak admin console credentials from the Docker Compose configuration are:

- Username: `admin`
- Password: `admin`

## API Usage

All public requests are sent through Nginx on port 80. Direct gateway requests on port 8080 are also available.

### Obtain a JWT Access Token

```powershell
$token = (Invoke-RestMethod `
  -Uri "http://localhost:8180/realms/photopulse/protocol/openid-connect/token" `
  -Method Post `
  -ContentType "application/x-www-form-urlencoded" `
  -Body @{
      client_id  = "photopulse-client"
      grant_type = "password"
      username   = "officer"
      password   = "parola123"
  }).access_token
```

### Upload a Camera Manual

Requires `ROLE_COMPLIANCE_OFFICER` or `ROLE_ADMIN`.

```powershell
curl.exe -X POST "http://localhost/api/v1/ingestion/upload" `
  -H "Authorization: Bearer $token" `
  -F "file=@fujifilm-ga645-zi-manual.pdf" `
  -F "cameraModel=Fujifilm GA645" `
  -F "brand=Fujifilm" `
  -F "tenantId=studio-brasov"
```

Supported upload fields:

| Field | Description |
|---|---|
| `file` | PDF, DOCX, or TXT manual |
| `cameraModel` | Exact camera model used for isolation and filtering |
| `brand` | Camera manufacturer |
| `tenantId` | Tenant identifier used for multi-tenant isolation |

### Ask the RAG Assistant

Requires an authenticated JWT:

```powershell
$body = @{
    question    = "How do I load film and set the ISO?"
    cameraModel = "Fujifilm GA645"
    tenantId    = "studio-brasov"
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri "http://localhost/api/v1/assistant/ask" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body $body |
  ConvertTo-Json -Depth 5
```

### Run Gap Analysis

```powershell
$gapBody = @{
    cameraModel     = "Fujifilm GA645"
    tenantId        = "studio-brasov"
    targetChecklist = @(
        "Autofocus system support"
        "Built-in flash modes"
        "4K 60fps video recording"
    ) -join "`n"
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri "http://localhost/api/v1/assistant/gap-analysis" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body $gapBody |
  ConvertTo-Json -Depth 5
```

## API Endpoints

| Method | Endpoint | Required role |
|---|---|---|
| `POST` | `/api/v1/ingestion/upload` | `ROLE_COMPLIANCE_OFFICER` or `ROLE_ADMIN` |
| `POST` | `/api/v1/assistant/ask` | Any valid authenticated role |
| `POST` | `/api/v1/assistant/gap-analysis` | Any valid authenticated role |

## Project Structure

```text
.
├── api-gateway/          # Spring Cloud gateway and JWT RBAC
├── assistant-service/    # RAG, gap analysis, Ollama, and audit logs
├── discovery-server/     # Netflix Eureka service registry
├── docker/               # Nginx configuration
├── ingestion-service/    # Tika ingestion and hierarchical chunking
├── docker-compose.yml    # PostgreSQL, Qdrant, Keycloak, and Nginx
├── start-all.ps1         # Ecosystem startup automation
└── stop-all.ps1          # Ecosystem shutdown automation
```

## Data and Isolation

PostgreSQL stores document ingestion status and assistant audit records. Qdrant stores vector embeddings and associated metadata. Every assistant search applies `tenant_id` and `camera_model` filters so that results from another tenant or camera model cannot be returned.

The PostgreSQL and Qdrant Docker volumes preserve data across container restarts:

- `postgres_data`
- `qdrant_data`
