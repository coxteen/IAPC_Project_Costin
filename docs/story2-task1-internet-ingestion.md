# Story 2 – Task 1: Dynamic Internet Ingestion

## 1. Overview
Extends `ingestion-service` with an HTTP/HTTPS batch ingestion pipeline (`POST /api/v1/ingestion/internet`) capable of fetching, cleaning, chunking, and indexing external photography documentation into Qdrant while enforcing source attribution and fault tolerance.

---

## 2. Supported Source Types

| Source Type | Processing Pipeline | Key Behavior |
| :--- | :--- | :--- |
| **`WEB_PAGE`** | `HttpClient` -> `HtmlBoilerplateCleaner` (Jsoup) -> `StructureAwareChunker` | Strips `<nav>`, `<header>`, `<footer>`, `<aside>`, `<script>`, `<style>`, and cookie/GDPR/popup banners before extracting main article content (`<article>`, `<main>`, `[role=main]`). |
| **`REMOTE_PDF`** | `HttpClient` -> `DocumentIngestionService.parseAndChunk` (Apache PDFBox) | Validates `%PDF` magic bytes in memory, extracts page-by-page text (`setSortByPosition(true)`), and enriches chunks with internet metadata. |
| **`GITHUB_REPO`** | GitHub Trees API -> `raw.githubusercontent.com` -> `StructureAwareChunker` | Resolves default/target branch, discovers `.md`, `.txt`, and `.rst` documentation files (up to `max-repo-files`), and indexes each file with repo path metadata. |

---

## 3. Metadata Schema & Fault Tolerance

- **Mandatory Internet Metadata (`Story 2 – Task 1`):** Every indexed chunk stores `source_url` (exact fetched URL) and `fetch_date` (ISO-8601 UTC timestamp), alongside the base metadata schema (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, `tenant_id`, `camera_model`, `brand`).
- **Batch Resilience:** Each source in a `DynamicBatchRequest` is processed independently. If a single URL fails (e.g., `HTTP 404`, timeout, invalid PDF header, or empty content), the error is logged and recorded as `FAILED` in the response without aborting the remaining items in the batch.

---

## 4. Configuration (`application.yml`)

| Property | Environment Variable | Default | Description |
| :--- | :--- | :---: | :--- |
| `app.dynamic-ingestion.connect-timeout-ms` | `APP_DYNAMIC_CONNECT_TIMEOUT_MS` | `10000` | TCP connection timeout for outbound HTTP requests |
| `app.dynamic-ingestion.read-timeout-ms` | `APP_DYNAMIC_READ_TIMEOUT_MS` | `15000` | Response read timeout per remote resource |
| `app.dynamic-ingestion.max-repo-files` | `APP_DYNAMIC_MAX_REPO_FILES` | `15` | Maximum documentation files indexed per GitHub repository |
| `app.dynamic-ingestion.user-agent` | — | `PhotoPulse-IngestionBot/1.0` | User-Agent header sent to remote servers and GitHub API |

---

## 5. API Usage Example (PowerShell)

```powershell
\$internetBody = @{
    tenantId = "studio-brasov"
    version  = "1.0"
    sources  = @(
        @{
            type        = "WEB_PAGE"
            url         = "[https://example.com/guides/autofocus-tips](https://example.com/guides/autofocus-tips)"
            cameraModel = "Sony A7 IV"
            brand       = "Sony"
        },
        @{
            type        = "REMOTE_PDF"
            url         = "[https://example.com/manuals/quick-guide.pdf](https://example.com/manuals/quick-guide.pdf)"
            cameraModel = "Canon EOS R6"
            brand       = "Canon"
        },
        @{
            type        = "GITHUB_REPO"
            url         = "[https://github.com/open-photo/camera-profiles](https://github.com/open-photo/camera-profiles)"
            cameraModel = "Fujifilm X-T5"
            brand       = "Fujifilm"
        }
    )
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Uri "http://localhost/api/v1/ingestion/internet" `
  -Method Post `
  -Headers @{ Authorization = "Bearer \$token" } `
  -ContentType "application/json" `
  -Body \$internetBody | ConvertTo-Json -Depth 5