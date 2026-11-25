# Story 3 – Task 1: Safe Metadata Filtering

## 1. Overview
Adds a strict, AST-backed metadata filter parser (`SafeMetadataFilterParser`) to `assistant-service` so that URL query parameters (`?filter=...`) are parsed, validated against a strict allowlist, and compiled into a typed Spring AI `Filter.Expression` before reaching Qdrant. Raw strings never reach the vector store.

---

## 2. Whitelisted Fields & Operators

| Category | Allowed Values | Enforcement Behavior |
| :--- | :--- | :--- |
| **Metadata Fields** | `tenant_id`, `camera_model`, `brand`, `doc_id`, `version`, `source_type` | Any unknown field throws `InvalidMetadataFilterException` (`HTTP 400`) listing all valid fields. |
| **Comparison Operators** | `eq` (`=`), `ne` (`!=`), `in` | Unsupported operators (e.g., `regex`, `like`) are rejected with `HTTP 400`. |
| **Logical Combinators** | `AND`, `OR`, parentheses `(...)` | Supports nested compound expressions; `AND` binds tighter than `OR`. |
| **Value Sanitization** | `^[a-zA-Z0-9_.:/@\- ]{1,128}$` | Rejects semicolons, quotes, and injection payloads before query expansion or vector search. |

---

## 3. Endpoints & Fallthrough Behavior

- **`GET /api/v1/assistant/ask?question=...&tenantId=...&filter=...`** and **`POST /api/v1/assistant/ask?filter=...`**
- **Null / Blank Filter Fallthrough:** When `filter` is omitted or blank, the pipeline falls back to the standard tenant-isolated filter (`tenant_id == <tenantId>` and optional `camera_model == <cameraModel>`).
- **Explicit `400 Bad Request` Error Payload:**
```json
{
  "timestamp": "2026-11-25T12:00:00Z",
  "status": 400,
  "error": "Invalid Metadata Filter",
  "message": "Metadata field 'unknown_field' is not allowed",
  "invalidToken": "unknown_field",
  "validFields": ["tenant_id", "camera_model", "brand", "doc_id", "version", "source_type"],
  "validOperators": ["eq", "ne", "in", "AND", "OR"]
}


## 4. Postman Collection

A ready-to-import Postman v2.1 collection covering single filters, compound AND/OR/IN queries, null-filter fallthrough, and 400 Bad Request negative tests is committed at:postman/story3-task1-safe-metadata-filtering.postman_collection.json