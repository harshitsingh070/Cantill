# API reference

Base URL: `http://localhost:8080/api`. JSON unless noted. Errors return
`{ "error": "...", "message": "..." }` with status 400 for validation and 503 for AI unavailability.

## Batches and decisions

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/process-batch` | Multipart `file` (CSV), optional `uploadedBy`. Runs the engine, stores the run, returns `runId`, `policyVersionLabel`, `result`. |
| `POST` | `/process-batch/sample` | Runs the bundled sample CSV. |
| `GET` | `/runs` | Run summaries (`id`, `filename`, `policy_version_label`, `uploaded_by`, `created_at`). |
| `GET` | `/runs/{id}` | Full `EngineResult`: `lines`, `requirements`, `decisions`, `poGroups`. |
| `GET` | `/runs/{id}/workspace` | Buyer workspace: requirements with PR lines, preferred suppliers, contract headroom and policy trace; POs; sourcing events; row-level issues. |
| `GET` | `/runs/{id}/export.csv` | 13-column decisions CSV. |
| `GET` | `/decisions?run_id={id}` | Stored decisions with database IDs for overrides. |
| `GET` | `/audit` | Audit log rows. |
| `POST` | `/decisions/{id}/override` | Override a decision. Body: `comment` (required), `supplier_id`/`supplierId`, optional `route`. |
| `POST` | `/decisions/{id}/approve` | Approve a decision. Body: optional `actor`. Marks a linked PO `APPROVED` when all its decisions are approved; marks a sourcing event `APPROVED`. Approval is idempotent. |
| `POST` | `/sourcing-events/{id}/send` | Record that an approved RFQ/RFP was sent. Body: optional `actor`. Moves `APPROVED` to `SENT`. |

Run keys are deterministic: input hash + latest activated policy. Repeating a batch returns the stored result.

## Preferred suppliers

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/preferred-suppliers?as_of_date=YYYY-MM-DD&item_code=ITM-1036` | Ranked suppliers; omit `item_code` for all items. |
| `GET` | `/preferred-suppliers/export.csv?as_of_date=...&item_code=...` | Top three per item. |

## Policies

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/policies` | Policy versions. |
| `GET` | `/policies/{versionId}` | `{ version, rules, parameters, facts }`. |
| `GET` | `/policy-facts` | Registered fact keys and resolvers. |
| `POST` | `/policies/describe` | AI proposal from text (`text`, optional `today`, `effective_from`). |
| `POST` | `/policies/paste` | Alias for extraction from pasted text. |
| `POST` | `/policies/upload` | Multipart `file` (PDF); AI extraction proposal. |

## Policy amendments

States: `DRAFT → VALIDATED → APPROVED → APPLIED`; `REJECTED` is terminal. Only applied/approved amendments create
a version. Chat never approves or activates.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/policy-amendments` | List amendments. |
| `POST` | `/policy-amendments` | Create draft. Body: `effective_from`, `operations[]`, optional `source`, `raw_text`, `document_ref`, `created_by`. |
| `GET` | `/policy-amendments/{id}` | Amendment with operations and status. |
| `GET` | `/policy-amendments/{id}/draft` | Materialised draft (`effective_from`, `rules`, `parameters`). |
| `POST` | `/policy-amendments/{id}/validate` | Validate; moves to `VALIDATED` or rejects atomically. |
| `POST` | `/policy-amendments/{id}/preview` | Validate + plain-language `changes[]` + content hash. |
| `GET` | `/policy-amendments/{id}/diff` | Stored/generated diff. |
| `POST` | `/policy-amendments/{id}/rules` | Add rule operation. |
| `PATCH` | `/policy-amendments/{id}/rules/{ruleKey}` | Update rule operation. |
| `DELETE` | `/policy-amendments/{id}/rules/{ruleKey}` | Remove rule operation. |
| `POST` | `/policy-amendments/{id}/rules/{ruleKey}/reorder` | Set priority. |
| `POST` | `/policy-amendments/{id}/parameters` | Set parameter value/scope. |
| `PATCH` | `/policy-amendments/{id}/parameters/{key}` | Update parameter. |
| `POST` | `/policy-amendments/{id}/approve` | Approve and create a version; `two_step=true` leaves it `APPROVED` for later activation. |
| `POST` | `/policy-amendments/{id}/reject` | Reject; terminal. |
| `POST` | `/policy-amendments/{id}/activate` | Activate an `APPROVED` version (`ACTIVE` or `SCHEDULED`). |

Operation shapes:

```json
{"type":"set_parameter","key":"spot_limit","value":100000,"scope_json":{"plant":"CHK1"}}
{"type":"add_rule","rule_key":"v5.1.7.1","priority":75,"name":"...","condition_json":"...","outcome_json":"..."}
{"type":"update_rule","rule_key":"v5.0.5.1","patch":{"priority":65}}
{"type":"remove_rule","rule_key":"v5.0.5.6"}
{"type":"reorder_rule","rule_key":"v5.0.5.2","priority":45}
```

## Contracts

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/contracts` | Contracts with baseline/run/remaining consumption and lines. |
| `GET` | `/contracts/{contractId}` | One contract. |
| `POST` | `/contracts` | Upsert contract and lines. |
| `POST` | `/contracts/upload` | Multipart PDF; returns extraction proposal. |
| `POST` | `/contracts/{contractId}/extract` | Extract with a forced contract ID. |

## PRs

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/prs` | Manual PR batches with lines. |
| `GET` | `/prs/{id}` | One manual PR batch. |
| `POST` | `/prs` | Create a manual PR fallback with `requester`, `department`, optional `pr_no`, and `lines[]`. |
| `POST` | `/prs/{id}/submit` | Submit the PR to the engine; returns `runId` and the batch result. |

## Chat

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/chat/messages` | Body: `message`, optional `session_id`, `requester`, `department`, `today`. Returns `reply`, `session_id`, `intent`, `stage`, `missing_slots`, `tools`, `draft`; may include `pr_id`, `amendment_id`, `diff`. |
| `GET` | `/chat/sessions/{id}/messages` | Persisted transcript. |

## AI service (Python)

Base URL: `http://localhost:8000`. Bearer token required when `CORVO_AI_TOKEN` is set.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | `{ status: "ok" }`. |
| `POST` | `/policy/describe` | `{ text, effective_from?, today? }` → `{ effective_from, summary, operations, warnings, confidence }`. |
| `POST` | `/policy/extract` | Same as describe. |
| `POST` | `/policy/upload` | Multipart `file`; PDF text extraction with manual fallback warnings. |
| `POST` | `/contract/extract` | Multipart `file`; contract fields and Schedule A lines. |
| `POST` | `/chat/parse` | `{ message, today? }` → intent and slot proposal. |
