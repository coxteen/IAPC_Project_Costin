# Story 1: Structure-Aware PDF Ingestion and Chunking

## What This Feature Does

Before RAG can answer a question, a camera manual must be converted into searchable text. `DocumentIngestionService` performs this process:

1. validate the uploaded format;
2. calculate a SHA-256 checksum;
3. extract text page by page;
4. clean extraction artifacts;
5. create parent and child chunks;
6. generate embeddings;
7. store chunks and metadata in Qdrant;
8. update the PostgreSQL ingestion status.

## PDF Extraction

For PDF files, Apache PDFBox is used. `PDFTextStripper.setSortByPosition(true)` asks PDFBox to return text in reading order. This is important for technical tables and multi-column pages: without sorting, text from two columns can be interleaved incorrectly.

Example conceptual output:

```text
Page 16
Shutter Speed
P: 2 seconds to 1/700 seconds
A: 2 seconds to 1/700 seconds
M: Bulb or 4 seconds to 1/700 seconds
```

PDF extraction is page-aware. The page number is stored in metadata, so a generated citation can refer to the exact source page.

For DOCX and TXT files, Spring AI's Apache Tika reader extracts text. A stable synthetic page number is assigned because these formats do not contain PDF page boundaries.

## Parent and Child Chunks

The project uses a parent-child strategy:

- a **parent chunk** is a larger context section;
- a **child chunk** is a smaller section embedded in Qdrant;
- `parent_id` links a child to its parent;
- `parent_content` contains the text sent to the language model.

Example:

```text
Parent 1
  Section on autofocus
  + Eye AF setup steps
  + Menu navigation
  + Troubleshooting

Child 1-A
  "Enable Eye AF in Menu > AF1..."

Child 1-B
  "Assign AF-ON to activate Eye AF..."
```

Vector similarity searches the children for precision. The service then expands the selected child to its parent context, avoiding a search based only on a tiny fragment.

## Configurable Parameters

| Parameter | Default | Purpose |
|---|---:|---|
| `app.ingestion.chunk-size` | 250 tokens | approximate size of an indexed child |
| `app.ingestion.chunk-overlap` | 50 tokens | text repeated between consecutive chunks |
| `app.ingestion.parent-chunk-size` | 1000 tokens | approximate size of context sent to the model |
| `app.ingestion.min-chunk-size-chars` | 80 characters | merges/removes very small trailing fragments |
| `app.ingestion.embedding-model` | `nomic-embed-text` | model used for embeddings |
| `app.ingestion.batch-size` | 20 chunks | number of vectors sent per indexing batch |

Environment variables can replace the defaults:

```powershell
$env:APP_CHUNK_SIZE="250"
$env:APP_CHUNK_OVERLAP="50"
$env:APP_PARENT_CHUNK_SIZE="1000"
$env:APP_EMBEDDING_MODEL="nomic-embed-text"
```

Internally, the chunker approximately converts tokens to characters using four characters per token. It searches for natural boundaries in this priority:

```text
paragraph break
    -> new line
    -> sentence ending
    -> space between words
    -> hard character limit
```

This is better than blindly cutting every 1000 characters because a cut can divide a menu step or table row.

## Chunk Metadata

Each Qdrant document contains values such as:

| Field | Example |
|---|---|
| `doc_id` | `studio-brasov_fujifilm-ga645_fujifilm-ga645-zi-manual-pdf` |
| `checksum` | SHA-256 of the source file |
| `tenant_id` | `studio-brasov` |
| `camera_model` | `Fujifilm GA645` |
| `brand` | `Fujifilm` |
| `file_name` | `fujifilm-ga645-zi-manual.pdf` |
| `page_number` | `16` |
| `parent_id` | page and parent index |
| `parent_content` | complete surrounding section |
| `embedding_model` | `nomic-embed-text` |

Metadata is both searchable data and source information. It prevents cross-tenant retrieval and enables page citations.

## Benchmark Profiles

`ChunkingBenchmarkTest` creates a synthetic four-page PDF containing a table and runs six configurations. The generated table is not a universal production benchmark, but it demonstrates the trade-offs that should be discussed.

| Profile | Child size | Overlap | Parent size | Main trade-off |
|---|---:|---:|---:|---|
| Fine | 120 | 0 | 600 | precise, less context |
| Fine with overlap | 120 | 30 | 600 | stronger boundary continuity |
| Balanced no overlap | 250 | 0 | 1000 | less redundancy |
| Balanced recommended | 250 | 50 | 1000 | 20% overlap with complete context |
| Dense overlap | 250 | 80 | 1200 | high redundancy |
| Coarse | 500 | 75 | 1500 | fewer chunks but broader semantic mixing |

The project defaults to 250/50/1000/80 because this profile is a practical balance:

- chunks are large enough to contain a complete section;
- overlap reduces loss at boundaries;
- parent content supplies enough context for an answer;
- the vector count remains manageable.

Results may vary with the structure and quality of a real PDF. The benchmark is primarily evidence of parameter reasoning, not a claim that the same numbers will generalize to every document.

## Idempotency and Status

The ingestion endpoint calculates `SHA-256` for the uploaded bytes. If the same checksum has already completed for the same tenant and camera model, the controller skips re-ingestion.

The `ManualDocument` record stores:

```text
PROCESSING -> COMPLETED
           -> FAILED
```

This lets the API report whether a manual is still being processed or whether extraction failed.