# Corvo Procurement PR-to-PO: Codebase Implementation Report

Generated: 17 November 2026

This report is based on the current source tree, schema, seed data, tests, and runtime configuration. `APPROACH.md` was deliberately not used. No application code was changed while producing this report.

## 1. Executive summary

The repository implements a full-stack procurement decision system:

- A Spring Boot 3.3.5 / Java 17 backend owns catalogue data, PR intake, policy versions, deterministic routing, contract arithmetic, supplier ranking, purchase orders, sourcing requests, approvals, overrides, audit records, and CSV exports.
- A React 18 / Vite frontend provides the buyer workspace and administrative consoles.
- A Python 3.12 / FastAPI service provides deterministic document extraction and chat proposals. It does not make procurement decisions.
- PostgreSQL 16 is used in Docker; H2 in PostgreSQL compatibility mode is used for local tests and non-Docker development.
- Flyway applies four schema migrations. Docker Compose runs the database, AI service, backend, and nginx-served frontend.

The main implemented flow is:

1. A PR arrives through CSV upload, the bundled sample, manual PR entry, or buyer chat.
2. The engine validates and resolves lines, clubs demand into requirements, values each requirement, selects the policy effective on the requirement date, evaluates policy rules in priority order, ranks suppliers, and produces decisions.
3. Batch processing persists PR lines, requirements, decisions with candidate PO IDs, sourcing events, and the serialized engine result. It does not create `pos` or `po_lines` rows.
4. Buyers inspect the run workspace, approve or override decisions, send approved sourcing requests, and export decisions. The first approval of a PO decision creates the PO and its lines.
5. Policy changes are materialized as new immutable, content-hashed versions through a validate/preview/approve/activate workflow.

Current verification performed for this report:

- `mvn test`: **38 tests, 0 failures, 0 errors, 0 skipped**.
- `npm run build`: successful Vite production build.
- `submission/`: empty.
- Docker Desktop was not running during the final check, so live container health was not rechecked. The code and Compose configuration remain deployment-ready.

## 2. Architecture

### 2.1 Runtime components

| Component | Location | Responsibility |
| --- | --- | --- |
| Backend API and engine | `backend/` | System of record and all deterministic decisions |
| Buyer UI | `frontend/` | React single-page workspace |
| AI/document service | `ai-service/` | PDF/text extraction and chat proposals |
| Database | PostgreSQL in Compose; H2 locally | Catalogue, policy, decisions, workflow, audit |
| Schema migrations | `backend/src/main/resources/db/migration/` | Flyway V1-V4 |
| Seed data | `backend/src/main/resources/seed/` | Catalogue, suppliers, PO history, contracts, sample PR batch |
| Deployment | `deploy/`, `docker-compose.yml` | Dockerfiles, nginx reverse proxy, health checks |
| Tests | `backend/src/test/java/com/corvo/procurement/` | Engine, policy, chat, override, and workspace checks |

### 2.2 Request flow

The normal batch path is:

`React / client -> BatchController -> BatchService -> CsvPrParser -> PrBatch/PrLine persistence -> EngineDataFactory -> DecisionEngine -> batch_runs/requirements/decisions/sourcing_events persistence -> workspace/approval/export APIs`

The engine path is:

`DecisionEngine` -> item resolution -> requirement clubbing -> contract/PO valuation -> policy rule evaluation -> preferred supplier ranking -> decision + trace -> deterministic PO grouping

The AI path is separate:

`PolicyController` or `ContractController` -> `AiClient` -> FastAPI `/policy/*` or `/contract/extract`

Batch processing never calls the AI service. If the AI service is unavailable, AI-only endpoints return `503`, while batch processing, policy activation, approvals, and exports continue to work.

## 3. Repository map

| Path | Purpose |
| --- | --- |
| `backend/src/main/java/com/corvo/procurement/api/` | REST controllers and API exception mapping |
| `backend/src/main/java/com/corvo/procurement/engine/` | Decision engine, item matcher, contract evaluator, preferred supplier engine |
| `backend/src/main/java/com/corvo/procurement/ingestion/` | CSV parser, batch processing, CSV export |
| `backend/src/main/java/com/corvo/procurement/policy/` | Policy selection, facts, parameters, rule evaluation, hashing |
| `backend/src/main/java/com/corvo/procurement/policy/amendment/` | Draft/validate/preview/approve/activate policy changes |
| `backend/src/main/java/com/corvo/procurement/audit/` | Decision override and audit log access |
| `backend/src/main/java/com/corvo/procurement/workflow/` | Approval, sourcing send, workspace projection |
| `backend/src/main/java/com/corvo/procurement/chat/` | Slot-filling PR and policy chat |
| `backend/src/main/java/com/corvo/procurement/pr/` | Manual PR creation and submission |
| `backend/src/main/java/com/corvo/procurement/contract/` | Contract listing, upsert, and extraction forwarding |
| `backend/src/main/java/com/corvo/procurement/ranking/` | Preferred supplier listing and CSV export |
| `backend/src/main/resources/seed/` | Idempotent seed inputs and sample batch |
| `frontend/src/App.jsx` | All UI tabs and workflows |
| `ai-service/app/main.py` | FastAPI proposal service |
| `docs/` | API reference, runbook, and this report |

## 4. Data model

The schema is split into six functional areas.

### 4.1 Catalogue and history

- `items`: item code, description, UOM, category, item type, standard price.
- `suppliers`: supplier identity, city, categories, status, on-time percentage, quality rating.
- `plants`: `PUN1` and `CHK1`.
- `po_history`: historical PO number, date, supplier, item, quantity, UOM, unit price, plant, on-time flag.
- `item_aliases`: alias-to-canonical-term mapping used by item matching.

### 4.2 Contracts

- `contracts`: supplier, start/end dates, value cap, baseline consumed value, source, status.
- `contract_lines`: item-level contract rates.
- `contract_consumption_runs`: audit-only record of value consumed by a run.

Contract ceilings are calculated by the engine from the immutable contract baseline plus consumption within the current run. `contract_consumption_runs` is not read back as the next baseline.

### 4.3 PR intake and requirements

- `pr_batches` / `pr_lines`: raw uploaded or manually entered PR data.
- `requirements`: clubbed demand with date, item, plant, urgency, valuation, contract, and new-item flag.
- `requirement_lines`: requirement-to-PR-line links.
- `batch_runs`: one row per engine run with run key, input hash, policy label, uploader, timestamp, and serialized result JSON.

### 4.4 Decisions and workflow

- `decisions`: route, supplier, price, value, suggestions, policy version, clause, reason, trace JSON, approval status, PO link, Capex approval flag.
- `decision_lines`: decision-to-PR-line links.
- `pos`: proposed purchase orders with supplier, plant, route, first requirement time, and approval status.
- `po_lines`: PO-to-requirement links.
- `sourcing_events`: RFQ/RFP/Capex/new-item sourcing artifacts with approval and send state.

### 4.5 Policy

- `policy_versions`: immutable version metadata, status, source, content hash, approval and activation timestamps.
- `policy_facts`: registered fact keys and resolver configuration.
- `policy_rules`: rule key, priority, clause, scope, condition JSON, outcome JSON, enabled flag.
- `policy_parameters`: parameter key, typed value, and scope.
- `policy_amendments`: draft, extracted data, operations, diff, status, and applied version.
- `policy_change_log`: per-change before/after records.

### 4.6 Audit, users, and chat

- `audit_log`: actor, action, target, before/after JSON, timestamp.
- `users`: schema present, but no authentication or authorization is wired.
- `chat_sessions` / `chat_messages`: persisted chat state and transcript.

Migrations V2-V4 add chat state, contract consumption indexes, override snapshot fields, workspace approval fields, `engine_requirement_id`, and indices for approval/PO/sourcing lookups.

## 5. Seed data and baseline policy

### 5.1 Seed volumes

| Seed file | Rows |
| --- | ---: |
| `items.csv` | 73 |
| `suppliers.csv` | 48 |
| `plants.csv` | 2 |
| `po_history.csv` | 468 |
| `contract_register.csv` | 10 |
| `contract_lines.csv` | 26 |
| `sample_pr_batch.csv` | 186 |

`SeedRunner` is idempotent per table and only seeds when the corresponding table is empty. It also adds two PDF-sourced contracts not present in the CSV register:

- `RC-2026-031` for supplier `S021`.
- `RC-2026-033` for supplier `S035`.

The alias seed includes 17 aliases such as `brg -> bearing`, `pneu -> pneumatic`, `ms -> mild steel`, and `stick electrode -> welding electrode`.

### 5.2 Baseline policy

The seeded policy is `v5.0`, document `CORVO-PROC-POL-04 v5.0`, effective `2026-04-01`, approved by `system` on `2026-03-25`, and activated on `2026-04-01`.

It contains:

- 13 facts.
- 24 parameters.
- 7 rules.

Important baseline parameters:

| Parameter | Value |
| --- | ---: |
| `emergency_limit` | 500000 |
| `spot_limit` | 200000 |
| `rfq_rfp_boundary` | 1000000 |
| `club_window_days` | 14 |
| `need_by_window_days` | 7 |
| `consolidation_hours` | 24 |
| `active_history_days` | 365 |
| `valuation_history_days` | 365 |
| `preferred_history_days` | 548 |
| `recency_fresh_days` | 90 |
| `recency_usable_days` | 365 |
| `suggestion_count_rfq` | 3 |
| `suggestion_count_rfp` | 5 |
| `suggestion_count_capex` | 5 |
| `item_match_threshold` | 0.50 |
| `item_match_token_weight` | 0.50 |
| `item_match_fuzzy_weight` | 0.50 |
| `ranking_weight_recency` | 0.40 |
| `ranking_weight_frequency` | 0.25 |
| `ranking_weight_contract` | 0.20 |
| `ranking_weight_on_time` | 0.10 |
| `ranking_weight_quality` | 0.05 |
| `topup_weight_on_time` | 0.50 |
| `topup_weight_quality` | 0.50 |

The seven seeded rules, in priority order, are:

| Priority | Rule | Clause | Route |
| ---: | --- | --- | --- |
| 10 | New item sourcing | 1.2 | `NEW_ITEM_SOURCING` |
| 20 | Capex sourcing | 5.1 | `CAPEX_SOURCING` |
| 30 | Contract PO | 5.2 | `CONTRACT_PO` |
| 40 | Emergency PO | 5.3 | `EMERGENCY_PO` |
| 50 | Spot PO | 5.4 | `SPOT_PO` |
| 60 | RFQ | 5.5 | `RFQ` |
| 70 | RFP | 5.6 | `RFP` |

The seeded sample batch produces 186 PR lines, 149 requirements, 149 decisions, and 82 PO candidates. Exactly two lines are `NOT_PROCESSED`: `PR-26-00172` because of invalid quantity and `PR-26-00173` because of an unknown plant. The batch stores candidate `po_id` values on decisions but creates zero `pos` rows; the first approval creates each PO.

## 6. Deterministic engine

### 6.1 Validation and item resolution

`DecisionEngine.validateAndResolve` applies these checks before a line can enter clubbing:

- Missing creation date: `NOT_PROCESSED`.
- Unknown plant: `NOT_PROCESSED`.
- Null or non-positive quantity: `NOT_PROCESSED`.
- Missing need-by date: `NOT_PROCESSED`.
- UOM mismatch against the catalogue item: `NOT_PROCESSED`.
- A valid item code takes precedence over the description and receives match score `1.0`.
- An invalid or blank item code falls back to description matching.
- Description matching uses aliases, token similarity, and fuzzy similarity. The threshold and weights are policy parameters.
- A below-threshold or unmatched description becomes a new item, with status `VALID_NEW_ITEM`.

The engine code at `DecisionEngine.java:137` treats a missing need-by date as a validation failure. The row remains in the engine result and CSV as `NOT_PROCESSED` with reason `missing need-by date`; it is not dropped. `ValidationWorkflowTest` asserts this behavior, and `DECISIONS.md` now records the same rule.

### 6.2 Clubbing

Valid lines are sorted by creation time, PR number, and PR line.

- Lines club when they have the same resolved item and plant.
- The requirement-date difference must be within `club_window_days`.
- The need-by-date difference must be within `need_by_window_days`.
- New items are never clubbed.
- If any line in a requirement is Emergency, the whole requirement becomes Emergency.
- Each requirement receives a deterministic engine ID in input processing order.

### 6.3 Valuation and contract applicability

For non-Capex items:

1. Contracts are considered applicable when the supplier is Active, the requirement date is within the contract term, and the contract has a line for the item.
2. A contract is usable when baseline consumption plus current-run consumption plus the prospective requirement value does not exceed the value cap.
3. The lowest-rate usable contract is selected.
4. If contracts exist but none has enough ceiling, the lowest applicable contract rate is used for valuation only; the contract route cannot be selected.
5. If no contract applies, valuation uses the most recent PO history within `valuation_history_days`, then the standard price.
6. PO-history valuation does not filter the historical supplier by current status, so a blocked supplier's historical price can still be a valuation reference.

For Capex items, valuation skips contracts and uses PO history or standard price.

`has_active_history` is separate from valuation: it is true only when an Active supplier has PO history for the item within `active_history_days` before the requirement date. Blocked or inactive suppliers never satisfy this test.

Contract consumption is tracked in memory per engine run. Consumption is applied after a `CONTRACT_PO` decision and never rewrites the seeded baseline.

### 6.4 Policy selection and rule evaluation

A policy version is selectable when it has an activation timestamp and its effective date is on or before the requirement date. Selection order is:

1. Effective date descending.
2. Approval timestamp descending.
3. Version ID descending.

If no version is effective, the line becomes `NOT_PROCESSED`.

Rules are evaluated in ascending priority. The first matching enabled rule wins. Conditions are evaluated against resolved policy facts and policy parameters.

Parameter scope resolution uses this specificity order:

1. `item_code`.
2. `category + plant`.
3. `plant`.
4. `category`.
5. Global scope.

### 6.5 Route outcomes

The current baseline outcomes are:

| Condition | Route | Decision |
| --- | --- | --- |
| New item | `NEW_ITEM_SOURCING` | `SOURCING_REQUEST` |
| Capex item | `CAPEX_SOURCING` | `SOURCING_REQUEST` |
| Usable contract with ceiling | `CONTRACT_PO` | `PO` |
| Emergency, value <= 500000, active history | `EMERGENCY_PO` | `PO` |
| Value < 200000, active history | `SPOT_PO` | `PO` |
| Value < 1000000 | `RFQ` | `SOURCING_REQUEST` |
| Value >= 1000000 | `RFP` | `SOURCING_REQUEST` |
| No matching rule or unresolvable data | `NOT_PROCESSED` | blank |

The numeric boundary behavior is intentional and tested:

- `emergency_limit` is inclusive (`lte`).
- `spot_limit` is strict (`lt`), so exactly 200000 falls through to RFQ.
- `rfq_rfp_boundary` is inclusive for RFP (`gte`), so exactly 1000000 is RFP.

Contract precedence means a usable contract wins before Emergency and Spot rules. Capex wins before Contract. New-item routing wins before everything else.

### 6.6 PO consolidation

Requirements with a `PO` decision are consolidated when they have the same supplier, plant, and route and fall within the consolidation window. `DecisionEngine.consolidate` resolves `consolidation_hours` from each candidate requirement's effective policy through `ParameterResolver`; if no policy is available it falls back to 24 hours.

PO IDs are deterministic sequential IDs in processing order: `PO-0001`, `PO-0002`, and so on. They are candidates until approval. If a candidate ID already exists in `pos` from another run, the stored PO uses a run-suffixed ID such as `PO-0001-R42`; the decision keeps the candidate ID.

`PolicyAcceptanceTest.consolidationHoursComeFromEffectivePolicy` proves that a 24-hour baseline creates two POs for requirements 25 hours apart and a 48-hour amendment merges them into one.

### 6.7 Preferred supplier ranking

The preferred supplier list is computed per item and as-of date.

Eligibility:

- Supplier must be Active.
- Supplier must have item PO history within `preferred_history_days` or a contract line for the item.
- Supplier must be Active.

Scoring:

`0.40 * recency + 0.25 * normalized PO frequency + 0.20 * contract bonus + 0.10 * on-time + 0.05 * quality`

Details:

- Recency is `1.0` when the latest PO is within 90 days, `0.5` when within 365 days, otherwise `0.0`.
- Frequency is the supplier's item-PO count divided by the maximum item-PO count among candidates.
- Contract bonus is `1.0` when the supplier has a contract line for the item, otherwise `0.0`.
- On-time is the supplier percentage divided by 100.
- Quality is the quality rating divided by 5.
- Ties are broken by supplier ID ascending.
- Reference price comes from the most recent PO, otherwise the latest contract line.
- Suggestions first take the preferred list, then top up from Active suppliers in the item category using `0.50 * on-time + 0.50 * quality`.
- Suggestions are never padded with ineligible suppliers.

The engine stores the full score components, reference price, reference source, contract flag, PO count, and recency bucket in the trace.

### 6.8 Decision trace

Each decision stores JSON containing:

- Requirement ID and item code.
- Requirement value and valuation source.
- Resolved policy facts.
- Matched rule key and route.
- Applicable contracts with before/after consumption, remaining ceiling, and usability.
- Preferred suppliers.
- Suggested supplier IDs.

This makes the decision inspectable without recomputing it from the UI.

## 7. Policy-as-data and amendments

### 7.1 Policy service

`PolicyService` exposes versions, rules, parameters, facts, snapshots, and latest activated policy metadata. `EngineDataFactory` builds an immutable engine snapshot from the database for each run.

### 7.2 Amendment lifecycle

The implementation uses:

- `DRAFT` on creation.
- `VALIDATED` after validation or preview.
- `APPLIED` after direct approval, or after later activation for a two-step amendment.
- `APPROVED` for a two-step amendment awaiting activation.
- `REJECTED` as a terminal state.

Supported operations include:

- `set_parameter`.
- `add_rule`.
- `update_rule`.
- `remove_rule`.
- `reorder_rule`.
- `set_effective_from`.

The service validates the entire materialized draft before creating a version. Invalid facts, missing parameters, invalid routes, duplicate rule keys or priorities, invalid scope keys, and Blocked/Inactive supplier outcomes reject the draft. Approval creates a new immutable version, clones the parent rules and parameters, applies operations, and records change-log and audit entries.

Preview produces a plain-language diff and content hash. If the draft changes after preview, approval rejects the stale preview. Two-step approval creates an `APPROVED` version without activation; activation sets the status to `ACTIVE` or `SCHEDULED` based on the effective date. Direct approval retires the prior active parent version.

Manual, chat-created, and extracted amendments share the same service path. Chat can create a draft only after explicit confirmation and cannot approve or activate it.

## 8. Buyer workflow

### 8.1 Batch processing

`BatchService`:

1. Parses CSV or accepts engine inputs.
2. Computes an input hash.
3. Builds a run key from the input hash and latest activated policy ID.
4. Returns an existing stored run when the key already exists.
5. Persists the PR batch and PR lines.
6. Runs the engine and persists requirements, decisions, decision lines, and sourcing events. Decision records carry candidate `po_id` values, but no `pos` or `po_lines` rows are created.
7. Stores the serialized `EngineResult` in `batch_runs`.

The run is idempotent by input hash plus latest activated policy. The uploader is recorded but is not part of the run key.

### 8.2 Approval

`DecisionWorkflowService.approve`:

- Rejects approval of `NOT_PROCESSED`.
- Is idempotent for an already approved decision.
- Moves the decision from `PROPOSED` to `APPROVED`.
- Records approver and timestamp.
- For a PO decision, creates the PO and all `po_lines` on the first approval if they do not exist, then sets the PO to `PARTIALLY_APPROVED` or `APPROVED` depending on whether every decision in the same run and candidate PO group is approved.
- For a sourcing decision, moves the sourcing event to `APPROVED`.
- Writes a `DECISION_APPROVE` audit entry.

### 8.3 Sourcing send

`DecisionWorkflowService.sendSourcing`:

- Allows only an `APPROVED` or already `SENT` event.
- Moves `APPROVED` to `SENT`.
- Records sender and timestamp.
- Writes a `SOURCING_SENT` audit entry.

### 8.4 Override

`AuditService.override` is the most stateful operation:

- A written comment is mandatory.
- The route must be known.
- A PO route requires an Active supplier and a positive unit price.
- Validation happens before any decision mutation.
- The first original decision is preserved in `original_decision_json`.
- The decision stores override comment, actor, and timestamp.
- PO routes create or reuse a PO and a PO line immediately, remove sourcing state, and detach stale PO lines. This works whether or not the batch had already created a PO row.
- Sourcing routes clear supplier, unit price, and PO state, detach PO lines, and create or reconcile a sourcing event.
- A sent sourcing event cannot be overridden.
- The operation writes a `DECISION_OVERRIDE` audit entry with before/after snapshots.

Override PO IDs use `PO-OVR-{decisionId}` with a numeric suffix if needed.

### 8.5 Workspace

`RunWorkspaceService` returns:

- Requirements with PR lines, match data, approval state, policy trace, preferred suppliers, and applicable contract headroom.
- Purchase orders built from persisted `po_lines` and `purchase_orders`, not from stale engine PO groups. Before the first approval the list is empty even though requirements carry candidate PO IDs.
- Sourcing events with approval and send timestamps.
- Row-level `NOT_PROCESSED` issues.
- Aggregated PO value and requirement summaries.

This persisted projection is what allows a PO created by an override to appear immediately in the buyer workspace.

### 8.6 CSV export

`CsvExporter` writes exactly 13 columns:

`pr_no,pr_line,resolved_item_code,group_id,po_id,decision,route,supplier_id,unit_price,group_value_inr,suggested_supplier_ids,policy_version,reason`

Blank values are empty strings. Monetary values use two decimals with half-up rounding. The sample export is one header plus 186 data rows.

## 9. Frontend

`frontend/src/App.jsx` is a single-page React application with ten tabs:

1. Buyer workspace.
2. Requirements.
3. Purchase orders.
4. Sourcing.
5. Manual PR.
6. Buyer chat.
7. Policy console.
8. Contracts.
9. Preferred suppliers.
10. Audit trail.

Implemented UI behavior includes:

- Process sample batch and upload CSV.
- Browse run history and inspect decisions.
- Filter requirements by plant, route, status, policy, and free text.
- Expand requirements to see PR lines, contract headroom, preferred suppliers, and policy trace.
- Approve decisions.
- Override routes with conditional supplier and unit-price prompts for PO routes.
- Inspect PO and sourcing states.
- Send approved sourcing events.
- Create and submit manual PRs.
- Chat for PR drafting and policy amendment drafting.
- Inspect policy versions, parameters, rules, and amendment diffs.
- Create or edit contracts and rate lines.
- View ranked preferred suppliers.
- View the audit trail.

The frontend uses a relative `/api` base. In Docker, nginx proxies `/api/` to the backend and serves the React SPA for other routes.

The Purchase Orders panel subtitle now matches the backend: proposed POs appear after their first requirement is approved, and the PO becomes approved when every requirement on it is approved.

## 10. API surface

### 10.1 Backend

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/process-batch` | Upload a CSV PR batch |
| `POST` | `/api/process-batch/sample` | Process the bundled sample |
| `GET` | `/api/runs` | List run summaries |
| `GET` | `/api/runs/{id}` | Get the serialized engine result |
| `GET` | `/api/runs/{id}/workspace` | Get requirements, POs, sourcing, and issues |
| `GET` | `/api/runs/{id}/export.csv` | Export 13-column decisions CSV |
| `GET` | `/api/decisions?run_id={id}` | List stored decisions for a run |
| `POST` | `/api/decisions/{id}/approve` | Approve a decision |
| `POST` | `/api/decisions/{id}/override` | Override a decision |
| `POST` | `/api/sourcing-events/{id}/send` | Mark an approved sourcing event sent |
| `GET` | `/api/audit` | List audit entries |
| `GET` | `/api/preferred-suppliers` | Rank suppliers by as-of date and optional item |
| `GET` | `/api/preferred-suppliers/export.csv` | Export top three preferred suppliers per item |
| `GET` | `/api/policies` | List policy versions |
| `GET` | `/api/policies/{versionId}` | Get version, rules, parameters, and facts |
| `GET` | `/api/policy-facts` | List registered facts |
| `POST` | `/api/policies/describe` | AI policy description proposal |
| `POST` | `/api/policies/paste` | AI extraction from pasted policy text |
| `POST` | `/api/policies/upload` | AI extraction from a PDF |
| `GET` | `/api/policy-amendments` | List amendments |
| `POST` | `/api/policy-amendments` | Create a draft amendment |
| `POST` | `/api/policy-amendments/{id}/validate` | Validate a draft |
| `POST` | `/api/policy-amendments/{id}/preview` | Validate and produce diff/hash |
| `GET` | `/api/policy-amendments/{id}/diff` | Get the stored diff |
| `GET` | `/api/policy-amendments/{id}/draft` | Get the materialized draft |
| `POST` | `/api/policy-amendments/{id}/approve` | Approve and create a version |
| `POST` | `/api/policy-amendments/{id}/reject` | Reject an amendment |
| `POST` | `/api/policy-amendments/{id}/activate` | Activate an approved version |
| `GET` | `/api/contracts` | List contracts with consumption |
| `GET` | `/api/contracts/{contractId}` | Get one contract |
| `POST` | `/api/contracts` | Create or replace a contract and its lines |
| `POST` | `/api/contracts/upload` | AI contract extraction |
| `POST` | `/api/contracts/{contractId}/extract` | Extraction with forced contract ID |
| `GET` | `/api/prs` | List manual PR batches |
| `GET` | `/api/prs/{id}` | Get one manual PR |
| `POST` | `/api/prs` | Create a manual PR |
| `POST` | `/api/prs/{id}/submit` | Submit a manual PR to the engine |
| `POST` | `/api/chat/messages` | Send a chat message |
| `GET` | `/api/chat/sessions/{id}/messages` | Get a transcript |

`ApiExceptionHandler` maps policy validation and ordinary argument errors to `400`, and AI unavailability to `503`, using `{ "error": "...", "message": "..." }`.

### 10.2 AI service

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Health check |
| `POST` | `/policy/describe` | Parse policy text into operations |
| `POST` | `/policy/extract` | Same extraction role |
| `POST` | `/policy/upload` | PDF text extraction |
| `POST` | `/contract/extract` | Contract field and line extraction |
| `POST` | `/chat/parse` | Chat intent and slot proposal |

The FastAPI service uses deterministic regex parsing and `pypdf`. It returns proposals, warnings, and confidence; it does not approve, activate, route, or persist procurement state. Its token check activates only when `CORVO_AI_TOKEN` is set.

## 11. Persistence, idempotency, and determinism

Determinism is implemented through:

- Sorted input processing by creation time, PR number, and PR line.
- Sorted requirement processing by date, first creation time, PR number, and line.
- Stable rule ordering by priority.
- Deterministic supplier tie-breaks by supplier ID.
- Contract selection by rate then contract ID.
- Sequential PO numbering.
- Exact `BigDecimal` arithmetic with half-up monetary rounding.
- Content-hashed policy versions.
- Run keys based on input hash plus latest activated policy ID.
- Immutable policy versions instead of in-place mutation.
- Run-scoped contract consumption from the baseline.

Non-obvious behavior to remember:

- `decision` is blank for `NOT_PROCESSED`; `route` carries the skipped state.
- New-item values are null when no estimated price is supplied.
- `contract_consumption_runs` is audit-only.
- `latestActivatedId()` includes scheduled activated versions, so activating a future policy changes the run key even for earlier requirement dates.
- `ContractService.list()` displays baseline consumption plus the sum of all run consumption, while the engine still uses only the baseline for each run.

## 12. Tests and verification

### 12.1 Test inventory

| Test class | Tests | Focus |
| --- | ---: | --- |
| `ChatFlowTest` | 4 | Policy draft confirmation, PR slot filling, chat submission, missing need-by, new-item price |
| `ContractEvaluatorTest` | 1 | Equal-rate contract tie-break: latest start date, then highest contract ID |
| `GoldenExamplesTest` | 5 | Sample batch, worked examples, edge cases, preferred lists, exact CSV |
| `OverrideWorkflowTest` | 5 | PO-to-sourcing, sourcing-to-PO, workspace reconciliation, invalid atomic override, blocked supplier |
| `PolicyAcceptanceTest` | 12 | Parameter changes, rule reordering, manual/extraction parity, scoped limits, synthetic history, tie-breaks, boundaries, consolidation window |
| `PolicyAmendmentTest` | 8 | Validation, scoping, stale preview, two-step activation, same-date tie-break |
| `ValidationWorkflowTest` | 1 | Missing need-by row retained as `NOT_PROCESSED` in engine result and CSV |
| `WorkspaceWorkflowTest` | 2 | Deferred PO creation on first approval and sourcing send; workspace shape before approval |
| **Total** | **38** | |

### 12.2 Verified results

`mvn test` currently reports:

- Tests run: 38.
- Failures: 0.
- Errors: 0.
- Skipped: 0.

`npm run build` completes successfully and produces the production bundle.

The Docker daemon was unavailable at final report time, so `docker compose ps` and live health URLs could not be checked. The Compose configuration is present and previously used for the local stack.

### 12.3 Documentation notes

- `README.md` says coverage includes "14 golden worked examples". `GoldenExamplesTest` has five test methods; its worked-example method asserts 15 sample rows and its edge-case method adds 7 more. The test code is the reliable count.
- The earlier drift findings are resolved in code and documentation: missing need-by rows are retained as `NOT_PROCESSED`, `consolidation_hours` is read from the effective policy, contract selection uses rate/latest start/highest ID, and POs are created on first approval rather than during the batch.

## 13. Deployment and operations

`docker-compose.yml` defines:

| Service | Port | Notes |
| --- | ---: | --- |
| `db` | 5433 | PostgreSQL 16, persistent volume, health check |
| `ai` | 8000 | FastAPI service, health check |
| `backend` | 8080 | Spring Boot API, depends on healthy DB |
| `web` | 5173 | nginx-served React SPA, depends on healthy backend |

The frontend nginx configuration:

- Proxies `/api/` to `backend:8080`.
- Serves `index.html` for SPA routes.
- Allows 25 MB request bodies.
- Uses a 120-second proxy read timeout.

Relevant backend environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATASOURCE_DRIVER_CLASS_NAME`
- `CORVO_AI_BASE_URL`
- `CORVO_AI_TOKEN`
- `CORVO_SEED_ENABLED`
- `PORT`

The AI client timeout is 4 seconds with one retry. Spring exposes only `health` and `info` actuator endpoints.

Hosted deployment is not provisioned. `README.md` contains a placeholder for the hosted URL; no Railway/Render credentials or hosted URL are present.

## 14. Known limitations and risks

- No authentication, SSO, or server-side role enforcement. The API trusts `requester`, `department`, `actor`, and `uploadedBy`.
- The `users` table exists but is not used for authorization.
- The Python service is deterministic, not an LLM. It can be swapped behind the same endpoints, but current extraction quality is bounded by regex and PDF text extraction.
- The AI service enables CORS for all origins and generates no decisions.
- PO candidate IDs are sequential per run. If the same candidate ID already exists from another run, the stored PO gets a run suffix while the decision keeps the candidate ID; this keeps rows unique but can make the displayed PO ID differ from the export candidate.
- No hosted deployment or public demo URL.
- Contract listing in the UI includes aggregate run consumption, but engine decisions always use the baseline; this is intentional but can be confusing when comparing UI remaining values to a specific run.
- No generic exception-to-JSON mapping for unexpected runtime failures beyond the three handled exception types.
- There is no explicit concurrency control around simultaneous batch runs or simultaneous overrides beyond normal database transaction behavior.

## 15. Current repository state

After the drift fixes:

- The working tree contains modified tracked files and many untracked implementation directories, reflecting the implemented stack.
- `submission/` is empty.
- No commits were made for this report or the fixes.
- Application source, tests, `DECISIONS.md`, and this report were updated for the four drift fixes.
- `mvn clean test` passes 38 tests, `npm run build` succeeds, and the Docker daemon remains unavailable for live container checks.

## 16. Summary of delivered work

The codebase now contains a working end-to-end PR-to-PO implementation with:

- CSV, manual, and chat intake.
- Deterministic item matching and requirement clubbing.
- Policy-driven routing with parameterized boundaries.
- Contract valuation and ceiling tracking.
- Preferred supplier ranking and top-up suggestions.
- PO candidates and sourcing events, with POs created on first approval.
- Buyer approval, override, and send workflows.
- Persisted workspace reconciliation.
- Policy amendment validate/preview/approve/activate lifecycle.
- Contract management and extraction proposal path.
- Audit trail and CSV exports.
- React buyer workspace and administrative consoles.
- Docker Compose deployment configuration.
- 38 passing backend tests and a successful frontend production build.
