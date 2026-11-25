# PhotoPulse AI Documentation

This directory explains the implemented data-processing and retrieval features for presentation to a teacher or reviewer.

## Documents

| Document | What it explains |
|---|---|
| [Story 1: chunking](./story1-task1-chunking-metrics.md) | PDF text extraction, parent-child chunks, overlap, benchmark profiles |
| [Story 2: internet ingestion](./story2-task1-internet-ingestion.md) | web pages, remote PDFs, GitHub repositories, batch fault tolerance |
| [Story 3: safe filtering](./story3-task1-safe-metadata-filtering.md) | filter grammar, validation, AST-like parser, HTTP 400 errors |

## Recommended Reading Order

1. Read the root [README](../README.md) for architecture and API examples.
2. Read Story 1 to understand how documents become searchable chunks.
3. Read Story 2 to understand the external-data ingestion pipeline.
4. Read Story 3 to understand multi-tenant filtering and why raw filter strings must not reach Qdrant.

## Main Vocabulary

- **Embedding**: a numerical vector representing the meaning of a text chunk.
- **Vector store**: Qdrant, used for similarity search over embeddings.
- **RAG**: Retrieval-Augmented Generation. Relevant text is retrieved first, then supplied to the language model.
- **Parent chunk**: the larger section stored as context.
- **Child chunk**: the smaller section stored and compared by vector similarity.
- **Tenant**: an organization or workspace whose documents must remain isolated.
- **Grounding**: making the model answer from supplied document context instead of invented knowledge.
