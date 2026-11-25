# PhotoPulse AI

PhotoPulse AI is a Java/Spring microservices project that helps users query camera manuals and online photography documentation. The system is built as a Retrieval-Augmented Generation (RAG) application:

1. documents are parsed and divided into chunks;
2. every chunk is converted into a vector embedding;
3. a question is expanded into several search queries;
4. Qdrant returns the most relevant chunks;
5. Ollama generates an answer using only the retrieved context;
6. the question, answer, and sources are saved as an audit record.

This design reduces hallucinations because the language model receives verified manual text instead of being asked to answer from memory.

## Architecture

```text
Browser / PowerShell / Postman
              |
              v
Nginx :80
              |
              v
API Gateway :8080 (JWT validation and role authorization)
              |
              +------> Eureka :8761
              |
              +------> Ingestion Service :8081
              |              |
              |              +--> PostgreSQL :5433
              |              +--> Ollama embeddings :11434
              |              +--> Qdrant :6334
              |
              +------> Assistant Service :8082
                             |
                             +--> PostgreSQL :5433
                             +--> Ollama chat + embeddings :11434
                             +--> Qdrant :6334

Keycloak :8180 provides authentication.
```

## Implemented Features

- File ingestion for PDF, DOCX, and TXT manuals.
- Parent-child chunking with configurable size and overlap.
- PDF extraction by page with Apache PDFBox and reading-order sorting.
- Embedding generation with `nomic-embed-text`.
- Vector indexing in Qdrant.
- Web page, remote PDF, and GitHub repository ingestion.
- HTML boilerplate removal with Jsoup.
- Tenant and camera-model isolation.
- Safe metadata filters for the assistant API.
- Multi-query expansion for more robust retrieval.
- Grounded answers and citations with file name and page number.
- Deterministic fallback when no relevant chunks are found.
- Gap analysis for a camera-model requirements checklist.
- PostgreSQL audit records for questions and answers.
- Keycloak/OIDC authentication and gateway role authorization.
- Docker infrastructure and PowerShell startup scripts.

## Technology

| Area | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.2 |
| Microservices | Eureka and Spring Cloud Gateway |
| AI/RAG | Spring AI 2.0.0-M1 |
| Chat model | Ollama `llama3.2` |
| Embedding model | Ollama `nomic-embed-text` |
| Vector database | Qdrant |
| Relational database | PostgreSQL 16 |
| PDF/Tika | Apache PDFBox and Apache Tika |
| Authentication | Keycloak, OAuth2/OpenID Connect |
| Infrastructure | Docker Compose and Nginx |

## Project Structure

```text
api-gateway/        JWT resource server and route security
assistant-service/  RAG, filtering, gap analysis, and audit logging
discovery-server/   Eureka service registry
docker/             Nginx reverse-proxy configuration
ingestion-service/  file and internet ingestion
docs/               implementation documentation
docker-compose.yml  PostgreSQL, Qdrant, Keycloak, and Nginx
start-all.ps1       start the complete ecosystem
stop-all.ps1        stop microservices and infrastructure
```

## Prerequisites

- JDK 25
- Docker Desktop
- Ollama
- PowerShell

Install the models used by the project:

```powershell
ollama pull llama3.2
ollama pull nomic-embed-text
```

## Start the Project

```powershell
.\start-all.ps1
```

The script starts Docker infrastructure, Ollama if needed, Eureka, the API gateway, and both business services. It does not create a Keycloak realm automatically; configure `photopulse` and the `photopulse-client` first if it is not already present.

Available dashboards and APIs:

| Component | Address |
|---|---|
| Public entry point | http://localhost |
| API gateway | http://localhost:8080 |
| Ingestion API | http://localhost:8081 |
| Assistant API | http://localhost:8082 |
| Eureka | http://localhost:8761 |
| Keycloak | http://localhost:8180 |
| Qdrant | http://localhost:6333/dashboard |

Stop the ecosystem with:

```powershell
.\stop-all.ps1
```

## Authentication Example

Requests pass through the API gateway. The following example obtains a password-grant token from Keycloak:

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

Uploading a document requires `ROLE_COMPLIANCE_OFFICER` or `ROLE_ADMIN`. Other API operations require any valid JWT.

## Upload a Manual

```powershell
curl.exe -X POST "http://localhost/api/v1/ingestion/upload" `
  -H "Authorization: Bearer $token" `
  -F "file=@fujifilm-ga645-zi-manual.pdf" `
  -F "cameraModel=Fujifilm GA645" `
  -F "brand=Fujifilm" `
  -F "tenantId=studio-brasov" `
  -F "version=1.0"
```

The response begins as `PROCESSING`. The document is then parsed asynchronously and either changes to `COMPLETED` or `FAILED`. Check status with:

```powershell
Invoke-RestMethod `
  -Uri "http://localhost/api/v1/ingestion/documents?tenantId=studio-brasov" `
  -Headers @{ Authorization = "Bearer $token" }
```

Duplicate detection uses SHA-256 and only skips re-ingestion when a completed document has the same checksum, tenant, and camera model.

## Ask a Question

```powershell
$body = @{
  question    = "How do I enable continuous autofocus?"
  cameraModel = "Sony A7 IV"
  tenantId    = "studio-brasov"
} | ConvertTo-Json

$response = Invoke-RestMethod `
  -Uri "http://localhost/api/v1/assistant/ask" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body $body

$response | ConvertTo-Json -Depth 6
```

A typical response contains:

- `auditId`: identifier of the PostgreSQL audit record;
- `answer`: the generated, grounded answer;
- `expandedQueries`: the original question plus generated variations;
- `citations`: filename, camera model, page, and text snippet.

The search applies `tenant_id` and, when supplied, `camera_model`. The generated answer is therefore scoped to that tenant and model.

If Qdrant returns no chunk above the similarity threshold, the service returns a clear "no matching information" message. It does not call the chat model, and it does not invent an answer.

## Metadata Filtering

The assistant accepts optional filters using URL parameter `filter` or a filter field in the JSON body.

Example:

```text
brand:eq:Sony AND (camera_model:eq:'Sony A7 IV' OR version:in:(1.0,2.0))
```

Supported fields are `tenant_id`, `camera_model`, `brand`, `doc_id`, `version`, and `source_type`. Supported operators are `eq`, `ne`, and `in`, with `AND`, `OR`, and parentheses. The raw filter is parsed by `SafeMetadataFilterParser`, validated, and converted to a Spring AI filter expression; invalid fields and unsafe values return HTTP 400.

```powershell
$url = "http://localhost/api/v1/assistant/ask?filter=brand:eq:Sony"
Invoke-RestMethod -Uri $url -Method Get `
  -Headers @{ Authorization = "Bearer $token" } `
  -Body @{ question = "How do I enable Eye AF?"; tenantId = "studio-brasov" }
```

See [Safe Metadata Filtering](./docs/story3-task1-safe-metadata-filtering.md) for the grammar and examples.

## Dynamic Internet Ingestion

```powershell
$body = @{
  tenantId = "studio-brasov"
  version  = "1.0"
  sources  = @(
    @{
      type        = "WEB_PAGE"
      url         = "https://example.com/sony-autofocus-guide"
      cameraModel = "Sony A7 IV"
      brand       = "Sony"
    },
    @{
      type        = "REMOTE_PDF"
      url         = "https://example.com/canon-r6-guide.pdf"
      cameraModel = "Canon EOS R6"
      brand       = "Canon"
    },
    @{
      type        = "GITHUB_REPO"
      url         = "https://github.com/example/camera-docs"
      cameraModel = "Fujifilm X-T5"
      brand       = "Fujifilm"
    }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri "http://localhost/api/v1/ingestion/internet" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body $body |
  ConvertTo-Json -Depth 5
```

Each source is processed independently. A 404 or parsing error affects only that source; the other entries continue.

## Gap Analysis

```powershell
$body = @{
  cameraModel = "Fujifilm GA645"
  tenantId    = "studio-brasov"
  targetChecklist = @(
    "Autofocus support"
    "Built-in flash modes"
    "Manual exposure support"
  ) -join "`n"
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri "http://localhost/api/v1/assistant/gap-analysis" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body $body
```

The service retrieves relevant manual sections for each checklist item and asks the model to classify requirements as supported, missing, or undocumented and to provide recommendations.

## How Retrieval Works

For example, a question can be divided into this conceptual flow:

```text
"How do I configure Eye AF?"
        |
        v
3 semantic query variations + original question
        |
        v
Tenant/model filter + threshold 0.35
        |
        v
Unique parent chunks (up to 5 in context)
        |
        v
Ollama generates an answer
        |
        v
Citation and audit information
```

The vector database stores child chunks for precise matching, while each child metadata record points to a larger `parent_content` field. This gives retrieval precision and response-generation context at the same time.

## Tests

Run tests from each Maven module:

```powershell
cd ingestion-service
.\mvnw.cmd test
cd ..\assistant-service
.\mvnw.cmd test
cd ..\api-gateway
.\mvnw.cmd test
cd ..\discovery-server
.\mvnw.cmd test
```

Important test classes:

- `ChunkingBenchmarkTest`: compares chunk sizes and overlap strategies.
- `PdfIngestionTask1Test`: PDF extraction and chunk metadata.
- `InternetIngestionTask1Test`: web, remote PDF, GitHub, and batch-failure behavior.
- `SafeMetadataFilteringTask1Test`: parser safety and HTTP 400 behavior.
- Spring context tests in every microservice.

## Detailed Documentation

- [Documentation index](./docs/README.md)
- [Chunking and benchmark results](./docs/story1-task1-chunking-metrics.md)
- [Dynamic internet ingestion](./docs/story2-task1-internet-ingestion.md)
- [Safe metadata filtering](./docs/story3-task1-safe-metadata-filtering.md)
