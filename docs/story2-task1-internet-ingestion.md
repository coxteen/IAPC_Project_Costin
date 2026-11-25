# Story 2: Dynamic Internet Ingestion

## Purpose

`POST /api/v1/ingestion/internet` lets the ingestion service download and index sources that are not uploaded as local files. The implementation is in `DynamicInternetIngestionService`.

Every source is associated with the supplied tenant, camera model, brand, and version. HTTP and HTTPS are the only accepted URL schemes.

## Supported Sources

| Source type | Processing |
|---|---|
| `WEB_PAGE` | fetch HTML, remove boilerplate, chunk article text |
| `REMOTE_PDF` | fetch bytes, validate the `%PDF` header, use PDF chunking |
| `GITHUB_REPO` | query the GitHub API, find documentation files, fetch them from `raw.githubusercontent.com` |

## Web Page Processing

`HtmlBoilerplateCleaner` uses Jsoup:

1. remove `<nav>`, `<header>`, `<footer>`, `<aside>`, `<script>`, `<style>`, and cookie/GDPR elements;
2. prefer `<article>`, `<main>`, or `[role=main]`;
3. fall back to the body;
4. extract headings and paragraphs;
5. remove empty or repeated fragments.

Example input:

```html
<header>Menu and advertising</header>
<main>
  <article>
    <h1>Real-Time Eye AF</h1>
    <p>Set Focus Mode to AF-C and Focus Area to Tracking.</p>
  </article>
</main>
<footer>Privacy Policy</footer>
```

The chunk should contain the Eye AF paragraph, not the menu or privacy text.

## Remote PDF Processing

The implementation downloads the remote bytes and validates the first four bytes:

```text
%PDF
```

It then reuses `DocumentIngestionService.parseAndChunk`, preserving PDF page extraction and parent-child chunk metadata.

## GitHub Repository Processing

For a GitHub URL, the service:

1. extracts owner and repository;
2. reads `/git/trees/{branch}?recursive=1`;
3. optionally filters to a `/tree/{branch}/{path}` subdirectory;
4. keeps `.md`, `.txt`, and `.rst` files;
5. indexes at most `max-repo-files`;
6. skips individual unreadable files.

Example:

```text
https://github.com/example/camera-docs/tree/main/docs
```

The implementation scans the `docs` prefix and fetches matching documentation files.

## Internet Metadata

All indexed chunks contain attribution fields:

| Field | Meaning |
|---|---|
| `source_url` | exact fetched URL or raw file URL |
| `fetch_date` | ISO-8601 UTC fetch timestamp |
| `source_type` | `web_page`, `remote_pdf`, or `github_repo` |
| `repo_url` | source GitHub repository URL, for repository files |
| `repo_file_path` | repository path such as `docs/fujifilm-recipes.md` |

This allows a teacher or administrator to explain where an answer came from and when the source was retrieved.

## Batch Fault Tolerance

Each source has an independent `try/catch` in `ingestBatch`. For example:

```text
Source 1: success
Source 2: HTTP 404
Source 3: success
```

The result is:

```json
{
  "totalSources": 3,
  "succeeded": 2,
  "failed": 1
}
```

Source 2 is marked `FAILED`; sources 1 and 3 are still indexed. This is better than allowing one broken link to abort the complete request.

## Configuration

| Property | Default | Purpose |
|---|---:|---|
| `app.dynamic-ingestion.connect-timeout-ms` | 10000 ms | connection timeout |
| `app.dynamic-ingestion.read-timeout-ms` | 15000 ms | response timeout |
| `app.dynamic-ingestion.max-repo-files` | 15 | maximum documentation files per repository |
| `app.dynamic-ingestion.user-agent` | `PhotoPulse-IngestionBot/1.0` | outbound identification |

Environment variable equivalents:

```powershell
$env:APP_DYNAMIC_CONNECT_TIMEOUT_MS="10000"
$env:APP_DYNAMIC_READ_TIMEOUT_MS="15000"
$env:APP_DYNAMIC_MAX_REPO_FILES="15"
```

## PowerShell Example

```powershell
$internetBody = @{
  tenantId = "studio-brasov"
  version  = "1.0"
  sources  = @(
    @{
      type        = "WEB_PAGE"
      url         = "https://example.com/guides/autofocus-tips"
      cameraModel = "Sony A7 IV"
      brand       = "Sony"
    },
    @{
      type        = "REMOTE_PDF"
      url         = "https://example.com/manuals/quick-guide.pdf"
      cameraModel = "Canon EOS R6"
      brand       = "Canon"
    },
    @{
      type        = "GITHUB_REPO"
      url         = "https://github.com/open-photo/camera-profiles"
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
  -Body $internetBody |
  ConvertTo-Json -Depth 5
```

## Tests

`InternetIngestionTask1Test` verifies:

- Jsoup removes scripts, navigation, ads, and cookie banners;
- web-page chunks contain `source_url` and `fetch_date`;
- remote PDF files are recognized and parsed;
- GitHub Markdown and text files are indexed;
- one failed source does not stop valid sources;
- an empty source list is rejected by request validation.