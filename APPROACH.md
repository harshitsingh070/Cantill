# Corvo Engineering — Policy-Driven PR-to-PO App

## Complete Implementation Approach and Locked Specification

**Version:** 1.0
**Scope:** Cantill.ai Full-stack Intern Task — Round 1
**Policy baseline:** CORVO-PROC-POL-04 v5.0, effective 1 April 2026
**Document purpose:** This is the implementation blueprint for the app, the decision engine, the policy-as-data model, the Spring Boot + Python architecture, the test plan, and the blind-test runbook. It also records the ambiguous cases that must be written into `DECISIONS.md`.

---

## 0. How to use this document

- Build the deterministic engine first. It is the highest-value and highest-risk component.
- Keep the engine and the blind-test critical path independent of the AI service.
- Treat every "locked decision" in this document as a requirement, not a suggestion.
- Copy the items in Section 17 into the repo's `DECISIONS.md`, with the reasoning and alternatives considered.
- Copy the architecture and AI-tool disclosure into the repo's `APPROACH.md`.

---

## 1. Executive summary

Build a web app where Corvo employees raise purchase requests by chatting, and the system decides — by applying a versioned procurement policy stored as data — whether to place a purchase order or run a sourcing event.

The system must:

1. Accept PRs by chat or CSV upload.
2. Match free-text descriptions to the item master without inventing items.
3. Club PR lines into requirements without allowing split-PR evasion.
4. Value requirements using contract rates, PO history, or standard prices.
5. Check contract applicability and ceiling availability in requirement-date order.
6. Apply ordered decision rules to choose PO or sourcing and the exact route.
7. Build a preferred-supplier list per item from PO history and contracts.
8. Consolidate qualifying PO requirements.
9. Export one row per PR line in the exact scoring format.
10. Let Procurement upload or describe a policy change, review the diff, approve it, and activate it by effective date without code changes.
11. Keep every action auditable.

The architecture separates the deterministic procurement engine from the AI layer. Spring Boot owns the system of record and all decisions. Python handles LLM chat orchestration and document extraction. The batch path never depends on Python.

---

## 2. Verified data inventory

### 2.1 Master data

| Dataset | Verified contents |
|---|---|
| Items | 73 items, ITM-1001 to ITM-1073 |
| Suppliers | 48 suppliers, S001 to S048 |
| Plants | PUN1 (Bhosari), CHK1 (Chakan) |
| PO history | 468 PO rows / 468 distinct POs, 2025-06-01 to 2026-10-28 |
| Contracts | 10 in the register + 2 PDF-only |
| PR batch | 186 lines across 175 PR numbers, November 2026 |

The brief says "about 525 POs"; the CSV contains 468 rows with 468 distinct PO numbers. Treat 468 as authoritative.

Supplier status traps:

- **Blocked:** S004 (Metalink Alloys), S008 (Bolt & Nut House), S011 (Spark Welding Stores).
- **Inactive:** S031 (Udyogi Safety Dealer), S034 (Servo Lubes Dealer).

These suppliers must never receive a PO or be suggested for sourcing.

### 2.2 Contract register

| Contract | Supplier | Status | Validity | Ceiling | Consumed | Covered items |
|---|---|---|---|---|---|---|
| RC-2025-011 | S001 Bhosari Steel Traders | Active | 2025-04-01 to 2027-03-31 | 60,00,000 | 41,00,000 | ITM-1001, 1002, 1003 |
| RC-2025-014 | S009 Weldtech Supplies | Active | 2025-12-01 to 2026-11-30 | 15,00,000 | 6,40,000 | ITM-1015, 1016, 1019 |
| RC-2026-002 | S013 Industrial Bearings Co | Active | 2026-01-01 to 2026-12-31 | 12,00,000 | 11,62,000 | ITM-1021, 1022, 1023, 1024 |
| RC-2026-005 | S004 Metalink Alloys | **Blocked** | 2026-02-01 to 2027-01-31 | 35,00,000 | 9,00,000 | ITM-1006, 1007 — **not applicable** |
| RC-2026-007 | S029 SafeGuard Industrial Supplies | Active | 2026-01-01 to 2026-12-31 | 15,00,000 | 3,00,000 | ITM-1046, 1047, 1048, 1049 |
| RC-2026-009 | S025 Kaveri Corrugators | Active | 2026-02-01 to 2027-01-31 | 25,00,000 | 9,00,000 | ITM-1041 |
| RC-2025-020 | S033 Castrol Industrial Distributor | Active | 2025-10-01 to **2026-09-30** | 20,00,000 | 13,50,000 | ITM-1052, 1053 — **expired** |
| RC-2026-012 | S038 Sparkle Facility Services | Active | 2026-04-01 to 2027-03-31 | 30,00,000 | 13,00,000 | ITM-1061 |
| RC-2026-013 | S007 Precision Fasteners Co | Active | 2026-06-01 to 2027-05-31 | 8,00,000 | 2,50,000 | ITM-1009, 1010, 1011, 1013 |
| RC-2026-015 | S006 Unbrako Fastener Distributors | Active | 2026-07-01 to 2027-06-30 | 5,00,000 | 4,88,000 | ITM-1009, 1014 |
| RC-2026-031 | S021 Pneumatic Point | Active | **2026-11-15** to 2027-11-14 | 20,00,000 | 0 | ITM-1036, 1037, 1039 — **PDF only** |
| RC-2026-033 | S035 Infoline Systems | Active | **2026-11-20** to 2027-11-19 | 50,00,000 | 0 | ITM-1056, 1057 — **PDF only** |

All 12 contract PDFs were checked against the register/PDF-only values: supplier IDs and names, inclusive dates, ceilings, and Schedule A item codes and rates match. Status is the current supplier-master status; RC-2025-020 is expired even though S033 is Active.

Key consequences:

- RC-2026-005 is never usable because S004 is Blocked. ITM-1006 and ITM-1007 therefore have no applicable contract.
- RC-2025-020 is expired for November 2026 requirements, but it still qualifies S033 for the preferred-supplier list under 6.1(b).
- RC-2026-002 has only about ₹38,000 of ceiling left, and RC-2026-015 has only about ₹12,000 left. Ceiling checks dominate many decisions.
- RC-2026-031 and RC-2026-033 are binding from their inclusive start dates even though they are not in the register.

### 2.3 Sample PR batch quirks

- 39 lines have no item code and require description matching.
- 60 lines have no estimated unit price.
- One line has an invalid plant: PR-26-00173, PUN2.
- One line has zero quantity: PR-26-00172.
- Six PRs have multiple lines: PR-26-00164 (4), 00165 (2), 00166 (3), 00167 (2), 00168 (3), 00169 (3).
- Only two sample lines are genuinely new items: PR-26-00170 (Drinking water purifier 50 LPH) and PR-26-00171 (Ultrasonic thickness gauge).
- "EOT crane 5 ton" matches ITM-1071, and "Forklift electric 2 ton" matches ITM-1073. Both are Capex items and are not new.

---

## 3. Requirements traceability

| Brief requirement | Implementation |
|---|---|
| Web app with frontend, backend API, database | React/Next frontend, Spring Boot API, PostgreSQL |
| Chat intake, not forms | LLM slot filling through Python, validated and persisted by Spring Boot |
| Cope with vague or unknown items | Deterministic matcher + threshold, ask user, or NEW item |
| Upload PR batch CSV | Spring Boot CSV ingestion and validation |
| Policy as data, versioned, effective-dated | Policy versions, rule rows, parameter rows, `policy_facts` registry |
| Upload or describe a policy change | PDF/paste/chat extraction through Python, or the manual operations editor |
| Show plain-language changes and approve | Spring Boot validation, diff, approval, and activation workflow |
| Future category rules, limits, new plant rules | Generic condition tree + ordered rule rows + scoped `param` references and parameters |
| Policy limit changed without editing rules | Rule conditions use `param` leaves resolved per requirement (§6.3, §6.5, §14.6 P1/P5) |
| Rule scope evaluated consistently | `scope_json` normalized into one canonical `condition_json` (§6.2, §6.6) |
| New kind of rule without code | New combinations/scopes of registered facts; new facts need a resolver, documented in `DECISIONS.md` |
| Manual update when Python is down | Same operation model, validation, diff, approval, and activation APIs |
| Decision engine | Deterministic Spring Boot pipeline |
| Preferred supplier list per item | Ranking engine, explanation, top-3 CSV export |
| Buyer workspace with approve/override/audit | React screens + Spring Boot audit log |
| POST /api/process-batch | Spring Boot endpoint |
| Export decisions CSV | Spring Boot export in the exact 13-column format |
| Contracts register + PDF-only contracts | Register import + PDF extraction + manual add |
| Automated decision tests | 14 golden examples, edge cases, boundary tests, determinism tests |
| Policy amendment tests | §14.6 fixtures covering limits, scopes, reordering, chat, manual parity, and version selection |
| One-command local run | Docker Compose |
| Hosted link | Render/Railway deployment |
| DECISIONS.md and APPROACH.md | Written from Sections 17 and 18 |

---

## 4. Architecture

### 4.1 Stack

| Layer | Technology |
|---|---|
| Frontend | React / Next.js |
| Core API and engine | Spring Boot 3.x, Java 21 |
| AI and document service | Python 3.12+, FastAPI |
| Database | PostgreSQL |
| Migrations | Flyway |
| Local orchestration | Docker Compose |
| Hosting | Render or Railway (three services + Postgres) |

### 4.2 Responsibility split

| Concern | Spring Boot | Python |
|---|---|---|
| System of record and PostgreSQL writes | Yes | No direct writes |
| REST API, auth, sessions, audit | Yes | Internal service only |
| CSV ingestion and export | Yes | No |
| Batch processing | Yes, fully independent | Not in the hot path |
| Item resolution | Deterministic normalization, alias lexicon, fuzzy score | Advisory candidate lists in chat only |
| Clubbing, valuation, contracts, ceiling | Yes | No |
| Decision rules and policy evaluation | Yes | No |
| Preferred supplier ranking | Yes | No |
| PO consolidation | Yes | No |
| Policy versioning, diff, approval, activation | Yes | PDF/text extraction and plain-language summary |
| Contract register and PDF ingestion | Yes (validate and persist) | PDF text/table extraction |
| Chat orchestration | Session, auth, audit, proxy | LLM slot filling and tool calls |

### 4.3 Runtime flow

1. Browser calls Spring Boot. Spring Boot is the single public entry point.
2. Spring Boot calls Python over internal REST for chat extraction, policy PDF/text/chat draft extraction, and contract PDF extraction.
3. Python returns structured proposals only. It never makes a final procurement decision.
4. Spring Boot validates, persists, applies policy, computes diffs, and writes the audit trail.
5. Batch processing and exports never call Python.

### 4.4 Resilience and fallbacks

- Batch processing works identically with Python up or down.
- Python item ranking is advisory in chat only; it never decides a batch match.
- Policy console supports four input modes:
  1. PDF upload through Python.
  2. Paste extracted text, with manual mapping.
  3. Describe the change in chat, which drafts operations through Python.
  4. Manual rule and parameter editor in Spring Boot.
- Contract console always supports manual entry in addition to PDF extraction.
- PR chat degrades to a structured form fallback if Python is unavailable; policy chat degrades to paste/manual.
- Python calls use timeouts, retries, and a circuit breaker. No user-facing hang.

### 4.5 Repository layout

- `frontend/` — Next.js app.
- `backend/` — Spring Boot application:
  - `api` controllers, DTOs, validation
  - `domain` entities and repositories
  - `engine` deterministic decision engine
  - `policy` versions, rules, parameters, diff, activation
  - `ranking` preferred supplier scoring
  - `ingestion` CSV and contract import
  - `integration/ai` Python client
  - `resources/db/migration` Flyway migrations
- `ai-service/` — Python FastAPI:
  - `chat` orchestration and tools
  - `extract` policy and contract extraction
  - `match` advisory candidate ranking
  - `schemas` Pydantic models
  - `tests`
- `deploy/` — Dockerfiles and Docker Compose.
- `docs/` — `APPROACH.md`, `DECISIONS.md`, `API.md`.
- `submission/` — blind-test CSV outputs.

---

## 5. Domain model

Core tables:

- Master data:
  - `items` (code, description, UOM, category, item_type, standard_price)
  - `suppliers` (id, name, city, categories, status, on_time_pct, quality_rating)
  - `plants` (code, name)
  - `po_history` (po_number, po_date, supplier_id, item_code, quantity, UOM, unit_price, plant, delivered_on_time)
  - `item_aliases` (alias, canonical_term, language, source) — data, not code
- Contracts:
  - `contracts` (contract_id, supplier_id, start_date, end_date, value_cap, consumed_value, source, status)
  - `contract_lines` (contract_id, item_code, description, UOM, rate)
  - `contract_consumption_runs` (run_id, contract_id, requirement_id, amount) — audit only; never used as the next run's baseline
- PR intake:
  - `pr_batches` (id, filename, uploaded_by, uploaded_at, input_hash)
  - `pr_lines` (batch_id, pr_no, pr_line, created_at, requester, department, plant, raw_item_code, raw_description, quantity, raw_uom, estimated_unit_price, need_by_date, urgency, resolved_item_code, match_score, match_method, validation_status)
- Requirements:
  - `requirements` (id, requirement_date, first_created_at, item_code, plant, urgency, contract_id, unit_price, value, valuation_source)
  - `requirement_lines` (requirement_id, pr_line_id)
- Decisions:
  - `decisions` (requirement_id, decision, route, supplier_id, unit_price, group_value_inr, suggested_supplier_ids, policy_version, clause, source_reference, reason, trace_json)
- Preferred suppliers:
  - `preferred_supplier_snapshots` (item_code, as_of_date, policy_version, rank, supplier_id, score, score_components, reference_price, has_contract, po_count, recency_bucket)
- POs and sourcing:
  - `pos` (po_id, supplier_id, plant, route, created_from_requirement, first_requirement_at)
  - `po_lines` (po_id, requirement_id)
  - `sourcing_events` (id, route, requirement_id, suggested_supplier_ids, approval_status)
- Policy:
  - `policy_versions` (id, parent_version_id, label, document_ref, effective_from, status, source, content_hash, approved_by, approved_at, activated_at, created_at, notes)
  - `policy_facts` (fact_key, value_type, resolver_key, config_json, description, is_available)
  - `policy_rules` (id, version_id, rule_key, priority, clause_ref, name, scope_json, condition_json, outcome_json, enabled) — unique `(version_id, rule_key)` and `(version_id, priority)`; `condition_json` is canonical and already includes any scope, and `scope_json` is metadata only
  - `policy_parameters` (version_id, key, value_json, value_type, scope_json) — unique `(version_id, key, scope_json)`
  - `policy_amendments` (id, source, raw_text, document_ref, extracted_json, operations_json, diff_json, status, effective_from, created_by, approved_by, approved_at, applied_version_id, applied_at)
  - `policy_change_log` (version_id, operation, target, before_json, after_json, actor, created_at)
- Audit:
  - `users`, `audit_log`, `chat_sessions`, `chat_messages`, `batch_runs`

Store the engine trace for every decision. This is required for explainability and for reproducing scoring results.

---

## 6. Policy-as-data design

### 6.1 Versioning and selection

Each policy version stores:

- Label, parent version, and document reference.
- Effective-from date.
- Status: `DRAFT`, `APPROVED`, `SCHEDULED`, `ACTIVE`, `RETIRED`.
- Source: upload, paste, chat, or manual.
- Content hash.
- Approver and approval time.
- Activation time.

Status transitions:

- `DRAFT` is editable and never eligible.
- `APPROVED` is immutable but not yet activated; it is not eligible. It exists only for an optional two-step approval mode.
- `SCHEDULED` is activated with `effective_from` in the future.
- `ACTIVE` is activated and currently effective.
- `RETIRED` is a UI/audit label for a superseded version. Never delete or mutate it.

Activation:

- Default `approve` validates, creates an immutable version, and activates it atomically. If `effective_from <= today`, set `ACTIVE`; otherwise set `SCHEDULED`.
- The separate `activate` endpoint exists only for the optional two-step mode: it takes an `APPROVED` version and sets `activated_at`/`ACTIVE`/`SCHEDULED`. It is idempotent.
- The UI's primary action is "Approve and activate"; it calls both steps in one transaction.

Engine selection is by **requirement date**, not today's date:

- Eligible versions are those with `activated_at IS NOT NULL` and `effective_from <= requirement_date`.
- Choose the latest `effective_from`; tie-break by `approved_at` descending, then `id` descending.
- Status is not used to remove a version from historical selection; `SCHEDULED` becomes selectable once the requirement date reaches its `effective_from`.
- If no version is eligible, the row is `NOT_PROCESSED` with reason "no policy effective for requirement date". Never silently use a future version.

This is mandatory for the blind test, where the same batch is reprocessed after an amendment. A future-dated amendment must not change decisions for earlier requirement dates.

### 6.2 Rule rows

Each materialized rule row stores:

- `rule_key`: stable identifier used by amendments, for example `v5.0.5.2`.
- `priority`: integer, unique per version, lower first.
- Clause reference, for example `5.2` or `4.3`.
- Name.
- `condition_json`: canonical condition tree; any authoring scope is already merged into it.
- `outcome_json`: decision, route, supplier strategy, suggestion count, capex flag.
- `enabled` flag.

`scope_json` is an **authoring/metadata** convenience on `add_rule` and `update_rule` operations and in the manual editor. It is stored on the rule row for editing and diffing, but the evaluator never reads it. Before storage, it is normalized into `condition_json`:

- Allowed scope keys are `item_code`, `category`, `plant`, and `item_type`.
- Each scope entry becomes a leaf `{fact, op:"eq", value}`.
- Leaves are emitted in this order: `item_code`, `category`, `plant`, `item_type`.
- If `condition_json` is already `{"all":[...]}`, the scope leaves are prepended to that `all` array; otherwise the result is `{"all":[scopeLeaves..., condition_json]}`.
- If there is no scope, `condition_json` is unchanged.
- When an `update_rule` changes scope, the first `N` leaves of `condition_json` are the old scope leaves, where `N` is the old scope entry count. Remove exactly those, prepend the new scope leaves, and keep the core condition unchanged. If the new scope is empty, remove the old leading leaves and store the remaining core condition.

Example: scope `{"plant":"CHK1"}` plus condition `{"all":[{"fact":"urgency","op":"eq","value":"Emergency"},{"fact":"value_inr","op":"gte","value":400000}]}` becomes:

```json
{"all":[
  {"fact":"plant","op":"eq","value":"CHK1"},
  {"fact":"urgency","op":"eq","value":"Emergency"},
  {"fact":"value_inr","op":"gte","value":400000}
]}
```

The evaluator sees only `condition_json`; it has no separate scope concept. Evaluation is the first enabled matching rule by ascending priority. A rule row never overrides these engine guardrails:

- Blocked and Inactive suppliers never receive a PO or a suggestion.
- Contract validity and ceiling arithmetic.
- Requirement-date policy selection.
- The 13-column CSV contract.

If no rule matches, emit `NOT_PROCESSED` with reason "no policy rule matched"; do not invent a default route.

### 6.3 Facts registry

`policy_facts` is the whitelist of facts a rule may reference. Each row stores `fact_key`, `value_type`, `resolver_key`, `config_json`, `description`, and `is_available`. A fact referenced by an activated version cannot be disabled.

Baseline facts:

| Fact | Type | Meaning |
|---|---|---|
| `item_code` | string | Matched item code, or `NEW` |
| `item_type` | enum | `Standard` or `Capex` |
| `category` | string | Item category |
| `plant` | enum | `PUN1` or `CHK1` |
| `value_inr` | decimal or null | Requirement value |
| `urgency` | enum | `Normal` or `Emergency` |
| `quantity` | decimal | Requirement quantity |
| `uom` | string | Item UOM |
| `need_by_offset_days` | integer | Need-by date minus requirement date |
| `has_applicable_contract` | boolean | At least one applicable contract |
| `has_contract_with_ceiling` | boolean | At least one applicable contract has room for this requirement |
| `has_active_history` | boolean | Active supplier PO within `active_history_days` before the requirement |
| `is_new_item` | boolean | No confirmed item match |

`need_by_offset_days` is seeded explicitly:

```json
{"fact_key":"need_by_offset_days","value_type":"integer","resolver_key":"requirement_field","config_json":{"field":"need_by_date_minus_requirement_date"},"description":"need_by_date minus requirement_date in calendar days; negative means past due"}
```

`has_active_history` is seeded with `resolver_key=history_flag` and `config_json={"window_param":"active_history_days","supplier_status":"Active"}`. Its window is read from the policy version, not hardcoded.

Condition JSON grammar:

```json
{"all":[
  {"fact":"value_inr","op":"lt","param":"spot_limit"},
  {"any":[
    {"fact":"plant","op":"eq","value":"CHK1"},
    {"fact":"category","op":"eq","value":"Fasteners"}
  ]}
]}
```

A leaf must contain exactly one of `value` or `param`:

- `value` is a literal and is used only for fixed policy content, such as a CHK1-only boundary of `400000`.
- `param` names a parameter in the same policy version. The evaluator resolves it through the scoped parameter resolver in §6.5 using the current requirement's `RequirementFacts`.
- `param` is type-checked against the parameter's `value_type` and must exist at validation time.
- Seed v5.0 never duplicates a policy limit as a literal in a condition. Every baseline limit and history window in the seeded rules uses a `param` reference or a parameter-driven fact.

Operators: `eq`, `ne`, `in`, `gt`, `gte`, `lt`, `lte`, `between`, `exists`, `all`, `any`, `not`.

- `between` is inclusive and uses a two-element `value` array; `param` is for scalar comparisons only.
- `exists` means not null.
- Numeric comparisons on null are false.
- Type mismatches, unknown facts, unknown operators, unknown parameters, and unknown enum values are rejected at validation time.

Baseline v5.0 rule conditions:

| Rule | Condition |
|---|---|
| 5.3 | `urgency eq Emergency` AND `value_inr lte param:emergency_limit` AND `has_active_history eq true` |
| 5.4 | `value_inr lt param:spot_limit` AND `has_active_history eq true` |
| 5.5 | `value_inr lt param:rfq_rfp_boundary` |
| 5.6 | `value_inr gte param:rfq_rfp_boundary` |

Rule 5.1 uses `item_type eq Capex`; rule 5.2 is selected by the valuation/contract result, not by a numeric condition. `has_active_history` is computed from the same version's `active_history_days` parameter, so changing that window through `set_parameter` changes rules 5.3/5.4 behavior.

Baseline resolvers are `requirement_field`, `item_field`, `parameter`, `history_flag`, and `contract_flag`. A new fact that maps to one of these via `config_json`, for example `item_field` with `{"field":"standard_price"}`, is data-only.

No-code boundary: new rule combinations, scopes, priorities, thresholds, and outcomes using the registered facts and operators require no code. A genuinely new computation requires a new `resolver_key` implementation and a `policy_facts` row; record that boundary in `DECISIONS.md`.

### 6.4 Outcomes

`outcome_json` stores:

- `decision`: `PO` or `SOURCING_REQUEST`.
- `route`: one of the supported routes.
- `supplier_strategy`: `contract_lowest_rate_with_ceiling`, `first_preferred_supplier`, `preferred_top_n`, `category_top_n`, or `none`.
- `suggestion_count`: integer or null.
- `requires_capex_approval`: boolean.

Allowed combinations:

| Route | Supplier strategy | Suggestion count |
|---|---|---|
| `CONTRACT_PO` | `contract_lowest_rate_with_ceiling` | 0 |
| `EMERGENCY_PO` | `first_preferred_supplier` | 0 |
| `SPOT_PO` | `first_preferred_supplier` | 0 |
| `RFQ` | `preferred_top_n` or `category_top_n` | 3 |
| `RFP` | `preferred_top_n` or `category_top_n` | 5 |
| `CAPEX_SOURCING` | `none` | 5 |
| `NEW_ITEM_SOURCING` | `none` | 0 |
| `NOT_PROCESSED` | `none` | 0 |

`NOT_PROCESSED` has an empty `decision`; the route carries the state. A new route value requires a code change; a new rule using an existing route does not.

### 6.5 Parameters

`policy_parameters` stores typed values with optional `scope_json`. Baseline values:

| Key | Baseline |
|---|---:|
| `emergency_limit` | 5,00,000 |
| `spot_limit` | 2,00,000 |
| `rfq_rfp_boundary` | 10,00,000 |
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

`scope_json` may contain `item_code`, `category`, or `plant`; unknown scope keys are rejected. `category` and `plant` may appear together as a compound scope. `item_type` is not valid on parameters; express item-type behavior as a rule scope instead. Canonicalize scope key order as `item_code`, `category`, `plant` before uniqueness checks and hashing, so two equivalent scopes cannot coexist.

The resolver signature is `resolve(versionId, key, RequirementFacts facts)`. It must never look up by `(versionId, key)` alone, because a scoped parameter needs the requirement being evaluated.

Parameter resolution:

1. Filter to the version and key.
2. Keep rows whose scope matches the requirement's facts.
3. Choose the most specific scope: `item_code` > `category + plant` > `plant` > `category` > global.
4. Tie-break by higher parameter row `id`.
5. Return the typed value; if no row remains, the key is missing.

Concrete P5 example: `spot_limit=100000` scoped to category `Safety & PPE`, evaluating `ITM-1050` (Safety & PPE, PUN1):

1. `item_code=ITM-1050`: not found.
2. `category=Safety & PPE, plant=PUN1`: not found.
3. `plant=PUN1`: not found.
4. `category=Safety & PPE`: found, return `100000`.
5. For a non-Safety item, fall through to the global `200000`.

A missing required key, or a missing key referenced by a rule's `param`, is a validation error at activation time. At runtime the same condition is an engine error, not a silent `NOT_PROCESSED`.

The same resolver is used by ranking and top-up. For a catalogue-level preferred list, the context has `item_code` and `category`; `plant` is null, so plant-scoped values fall through to category/global. This is deterministic and does not invent a plant.

Changing any of these values is a policy change, not a code change. The baseline values must reproduce the worked examples and rankings in Section 14.

### 6.6 Amendment operations

Python and the manual editor both produce the same operation list. Operations apply to the parent version's materialized snapshot, and the new version stores the full resulting rule and parameter set, not a delta. Supported operations:

- `set_parameter` — `{key, value, scope_json}`.
- `add_rule` — `{rule_key, priority, clause_ref, name, scope_json?, condition_json, outcome_json, enabled}`.
- `update_rule` — `{rule_key, patch}`; `patch` may include `scope_json` or `condition_json` as authoring fields.
- `remove_rule` — `{rule_key}`.
- `reorder_rule` — `{rule_key, priority}`.
- `set_rule_enabled` — `{rule_key, enabled}`.
- `set_effective_from` — `{date}`.

Normalization runs after validation and before the version is materialized:

1. Merge `scope_json` into `condition_json` as a leading `all` node using the §6.2 algorithm.
2. Store the merged `condition_json` plus the `scope_json` metadata on the rule row.
3. The engine reads only `condition_json`; `scope_json` exists for editing and diffing.
4. Normalize before hashing, so preview, approval, and the manual path produce the same content hash. The hash covers canonical rule rows (`rule_key`, `priority`, merged `condition_json`, `outcome_json`, `enabled`) and all parameter rows; `scope_json` metadata is derivable and excluded.

`update_rule` treats `condition_json` as the core condition. If `scope_json` is omitted, the existing scope is reapplied; if `scope_json` is provided, it replaces the existing scope. With an empty new scope, it removes the old scope leaves. It never appends a second scope. The UI and extraction send core conditions, not pre-merged scope leaves.

Extraction response:

```json
{
  "effective_from": "2026-12-01",
  "summary": "Emergency limit changes to 3,00,000 for CHK1",
  "operations": [],
  "warnings": [],
  "confidence": 0.0
}
```

Amendment status: `DRAFT` → `VALIDATED` → `APPROVED` → `APPLIED`; `REJECTED` is terminal. Only `APPLIED` amendments create a policy version.

Spring Boot validates:

- JSON schema and value types.
- Valid facts, operators, routes, and strategies.
- Every condition leaf has exactly one of `value` or `param`; every `param` exists in the target version and has a compatible type.
- Rule scopes use only `item_code`, `category`, `plant`, or `item_type`; parameter scopes use only `item_code`, `category`, or `plant`.
- Unique `rule_key` and unique priorities per version.
- Effective date present and valid.
- No mutation of an activated version.
- Blocked/Inactive supplier guardrail.
- Required parameters present.
- Content hash unchanged between preview and approval; reject a stale draft.

### 6.7 Amendment workflow

1. Procurement uploads a policy PDF, pastes text, describes the change in chat, or opens the manual editor.
2. Python extracts a draft operation list, or the manual editor creates it directly.
3. Spring Boot validates, resolves `param` references, and normalizes scope into the canonical condition tree.
4. Spring Boot computes a plain-language diff against the version effective immediately before the amendment's `effective_from`:
   - Limits and windows changed.
   - Rules added, removed, reordered, scoped, enabled, or disabled.
   - Effective date.
   Example: "From 1 Dec 2026, the CHK1 emergency limit changes from ₹5,00,000 to ₹3,00,000; PUN1 remains ₹5,00,000."
   Diff shape: `{effective_from, summary, changes:[{kind, target, scope, before, after, plain_language}]}`.
5. Procurement reviews the diff, adds a comment, and approves. Only a Procurement-role user can approve or activate; every action is audited.
6. Approval atomically creates an immutable version and activates it: current versions become `ACTIVE`, future-dated versions become `SCHEDULED`. The optional two-step mode can leave an `APPROVED` version for a separate `activate` call.
7. The engine automatically uses the new version for eligible requirement dates.
8. The old version remains for audit and historical runs.

Chat policy-change flow:

- The assistant detects a policy-change intent, not a PR intent.
- It asks for the effective date and the exact scope if missing.
- It calls a `describe_policy_change` tool backed by `POST /api/policies/describe`.
- It shows the proposed operations and plain-language diff.
- It requires an explicit confirmation before creating a draft amendment.
- It never approves or activates a policy change itself.

Never mutate an activated version in place. Every amendment creates a new version.

### 6.8 Manual fallback

The manual editor uses the same operation model and the same validation, diff, approval, and activation APIs:

- Edit limits and windows.
- Add, remove, reorder, enable, and disable rules.
- Edit conditions and outcomes.
- Add plant-only, category-only, and item-only rules.
- Set the effective date.
- Preview the diff and approve.

This is the guaranteed path if Python is down, slow, or the PDF is image-only. It must not be a reduced-functionality path.

---

## 7. Decision engine

### 7.1 Pipeline

1. Ingest and validate.
2. Resolve items.
3. Club lines into requirements.
4. Select the policy version per requirement by requirement date, then value requirements and select contracts.
5. Apply that version's decision rules with `RequirementFacts` and the scoped `ParameterResolver`, computing the 1st preferred supplier on demand for rules 5.3/5.4. Rule rows expose only merged `condition_json`; parameter leaves resolve against the same version and the current requirement.
6. Build the full preferred supplier lists and top-3 export data.
7. Consolidate POs.
8. Emit one row per PR line.

Do not reorder these stages. Rules 5.3/5.4 need the preferred-list ranking, so the ranking function is available to step 5; step 6 materializes the full list and export.

### 7.2 Step 1 — Ingest and validate

- Parse CSV with the exact sample columns.
- Validate dates, quantity, plant, UOM, and required fields.
- Invalid rows become `NOT_PROCESSED` with a reason.
- Locked invalid cases:
  - Unknown plant, for example PUN2: `NOT_PROCESSED`, reason "unknown plant".
  - Quantity zero or negative: `NOT_PROCESSED`, reason "invalid quantity".
  - UOM mismatch with the item master: `NOT_PROCESSED`, reason "UOM mismatch — manual review"; there is no conversion table.
  - Missing need-by date: `NOT_PROCESSED` or hold for correction. Chat asks the user; batch holds.
- Record raw input for audit.

### 7.3 Step 2 — Item resolution

- If a valid item code is present, use it. Policy 1.1 says the code wins.
- If the code is present but invalid, attempt description matching and log the discrepancy.
- If the code is missing, match the description.
- Matching method:
  - Normalize case, punctuation, quotes, and whitespace.
  - Expand aliases from the `item_aliases` table, for example brg → bearing, pneu → pneumatic, hyd → hydraulic, welding rod → welding electrode.
  - Score = `item_match_token_weight × token Jaccard + item_match_fuzzy_weight × difflib ratio`, using the active policy version's parameters.
  - Optional semantic candidates from Python in chat only.
- Spring Boot's deterministic matcher plus the alias table is the sole deciding matcher for batch processing. Python never decides a batch match.
- If the best score is below `item_match_threshold` (baseline 0.50):
  - Batch: `resolved_item_code = NEW`, route `NEW_ITEM_SOURCING`, audit the raw description and top candidates.
  - Chat: present candidates and ask the user to choose or confirm "new item".
- Never invent an item code.
- A vague Capex-like description that is below threshold is treated as NEW, not CAPEX_SOURCING.
- `CAPEX_SOURCING` only applies to a confirmed match whose `item_type` is Capex.
- New items are never clubbed.
- New item valuation:
  - Value = quantity × requester estimate.
  - If the estimate is missing: value is NULL, `group_value_inr` is empty, route is `NEW_ITEM_SOURCING`, and the reason says "no estimate provided — value not calculable".
  - Because policy 1.2 always routes new items to sourcing, the missing value does not change the route.
- New items always use `NEW_ITEM_SOURCING`, even if the description resembles Capex. Policy 1.2 overrides 5.1.

### 7.4 Step 3 — Clubbing

Sort lines by:

1. Creation time.
2. PR number.
3. Line number.

A line joins an existing requirement only if all are true:

- Same resolved item.
- Same plant.
- Created within `club_window_days` (baseline 14) calendar days of the requirement's first line.
- Need-by date within `need_by_window_days` (baseline ±7) calendar days of the first line's need-by date.

If it could join more than one requirement, it joins the earliest.

Rules:

- New items are never clubbed.
- The requirement date is the date portion of the first line's creation timestamp.
- If any line is Emergency, the requirement is Emergency.
- Apply every value limit to the requirement total, never to individual PR lines.
- Record the constituent lines for audit.

### 7.5 Step 4 — Valuation and contract selection

For non-Capex requirements:

1. Build the applicable contract set:
   - Contract covers the item.
   - Requirement date is within the inclusive start and end dates.
   - Contract supplier is Active.
   - Blocked/Inactive contract suppliers are excluded entirely, including from any fallback that uses "applicable contract rates".
2. **If the applicable set is empty, skip to step 5.**
3. For each applicable contract, compute prospective value at its rate.
4. A contract is usable if `baseline_consumed + prospective_value <= ceiling`.
5. If at least one contract is usable:
   - Choose the lowest-rate usable contract (policy 4.3).
   - The valuation unit price is that contract rate.
   - Rule 5.2 will use that contract.
6. If the set is non-empty but none is usable:
   - The valuation unit price is the lowest rate among the applicable contracts.
   - No contract is used for the PO.
   - Do not consume ceiling.
   - Continue to rules 5.3–5.6.
7. If there are no applicable contracts:
   - Use the most recent PO price for the item in `valuation_history_days` (baseline 365) before the requirement date.
   - This is status-agnostic: a PO from a now-Blocked or Inactive supplier may set the price.
   - If no such PO exists, use the item master standard price.
8. Requirement value = total quantity × valuation unit price.

Capex nuance:

- Rule 5.1 is checked before 5.2.
- If the matched item type is Capex, no contract "will be used".
- Value via PO history or standard price; for a new item, use the estimate.
- In v5.0 no contract covers ITM-1069–1073, so this is moot for the sample, but it is a locked decision.

The valuation source is decided independently of the final route. A contract rate can value a requirement even when no contract PO is placed. The route never retroactively changes the valuation rate.

Processing order and ledger updates:

- Within a run, process requirements in `(requirement_date, first_line_created_at, pr_no, pr_line)` order. Do not rely on database insertion order.
- Initialize the in-memory `consumed` map from the register baseline and zero for PDF-only contracts.
- After each requirement that uses a contract, immediately add its value to the in-memory ledger for that contract before evaluating the next requirement.
- Never mutate the persisted baseline. This is what makes repeat runs deterministic and keeps the ceiling check consistent across multiple requirements on the same contract.

### 7.6 Step 5 — Decision rules

Apply in strict order; the first match wins.

| Rule | Condition | Decision | Route | Supplier and price |
|---|---|---|---|---|
| 5.1 | Item type is Capex | Sourcing | `CAPEX_SOURCING` | 5 suggestions; capex approval flag |
| 5.2 | A contract can be used (4.1–4.3) | PO | `CONTRACT_PO` | Contract supplier at contract rate |
| 5.3 | Emergency, `value_inr <= param:emergency_limit` (baseline ₹5,00,000), `has_active_history` | PO | `EMERGENCY_PO` | 1st preferred supplier at reference price |
| 5.4 | `value_inr < param:spot_limit` (baseline ₹2,00,000), `has_active_history` | PO | `SPOT_PO` | 1st preferred supplier at reference price |
| 5.5 | `value_inr < param:rfq_rfp_boundary` (baseline ₹10,00,000) | Sourcing | `RFQ` | 3 suggestions |
| 5.6 | `value_inr >= param:rfq_rfp_boundary` (baseline ₹10,00,000) | Sourcing | `RFP` | 5 suggestions |

Rule 5.7: Blocked or Inactive suppliers must never receive a PO or be suggested. This is an engine guardrail, not an overridable outcome.

The limits and history windows in this table are the baseline v5.0 parameter values. The seeded rule conditions reference `emergency_limit`, `spot_limit`, `rfq_rfp_boundary`, and `active_history_days` through `param` leaves; they do not duplicate the numbers. A policy amendment can change a parameter or add scoped rule rows, and the rule evaluator picks up the change without a rule edit.

Locked behaviors:

- 5.1 beats 5.2.
- 5.2 beats 5.3 and 5.4, including for emergency requirements.
- 5.3 uses `value_inr lte param:emergency_limit` (baseline ≤ ₹5,00,000).
- 5.4 uses `value_inr lt param:spot_limit` (baseline < ₹2,00,000).
- 5.5 uses `value_inr lt param:rfq_rfp_boundary` (baseline < ₹10,00,000).
- 5.6 uses `value_inr gte param:rfq_rfp_boundary` (baseline ≥ ₹10,00,000).
- "Bought from an Active supplier within `active_history_days`" requires a PO whose supplier is currently Active. A Blocked or Inactive supplier's PO does not satisfy 5.3 or 5.4.
- The valuation price under 3.1(b) may still come from a Blocked or Inactive supplier's PO. Valuation and supplier eligibility are separate.
- If fewer candidates exist than the requested suggestion count, return the available suppliers. Example 11 has four RFP suggestions because the category pool is exhausted.
- Emergency has no special effect when value > `param:emergency_limit` (baseline ₹5,00,000). If no usable contract exists and there is no Active supplier history, the emergency flag falls through to 5.5 or 5.6 according to value.

Boundary table:

| Value | Emergency + Active history | Non-emergency + Active history |
|---|---|---|
| exactly ₹2,00,000 | 5.3 applies → `EMERGENCY_PO` | 5.4 fails; 5.5 → `RFQ` |
| ₹1,99,999.99 | 5.3 applies → `EMERGENCY_PO` | 5.4 applies → `SPOT_PO` |
| exactly ₹5,00,000 | 5.3 applies (≤) → `EMERGENCY_PO` | 5.4 fails; 5.5 → `RFQ` |
| ₹5,00,000.01 | 5.3 fails; 5.5 → `RFQ` | 5.5 → `RFQ` |
| exactly ₹10,00,000 | 5.3 fails; 5.6 → `RFP` | 5.6 → `RFP` |
| ₹9,99,999.99 | 5.5 → `RFQ` | 5.5 → `RFQ` |

Emergency with no Active history: 5.3 fails; 5.4 also fails; 5.5 or 5.6 decides by value, and the emergency flag has no further effect.

### 7.7 Step 6 — Preferred supplier list

Compute per item as of the requirement date. The as-of date also selects the policy version for ranking parameters; if no version is effective, return an empty list with an explicit error rather than using a future version. Resolve ranking parameters through the same scoped resolver as §6.5, with `item_code` and `category` in the context and `plant=null` for a catalogue-level list.

Who is on the list (6.1):

- Every Active supplier that either:
  - Has at least one PO for the item in `preferred_history_days` (baseline 548) before the date, or
  - Has a contract covering the item that started on or before the date, even if it has since expired.
- Blocked and Inactive suppliers are excluded.

Ranking (6.2):

- Design a transparent weighted score.
- Consider:
  - PO frequency in `preferred_history_days` (baseline 548).
  - Recency: 1.0 if the last PO is within 90 days, 0.5 if within 365 days, 0.0 if older.
  - Contract-holder bonus, including expired contracts that started on or before the date.
  - On-time percentage.
  - Quality rating.
- Reference formula, read from the active policy version's ranking parameters and verified to reproduce all three worked-example rankings:
  - `score = ranking_weight_recency × recency + ranking_weight_frequency × (po_count / max_po_count) + ranking_weight_contract × has_contract + ranking_weight_on_time × (on_time_pct / 100) + ranking_weight_quality × (quality_rating / 5)`
  - Baseline weights: `0.40`, `0.25`, `0.20`, `0.10`, `0.05`.
  - `po_count` uses `preferred_history_days` (baseline 548).
  - `has_contract = 1` if an Active supplier has a contract covering the item that started on or before the as-of date, including expired contracts; otherwise `0`.
  - Recency uses `recency_fresh_days` and `recency_usable_days` (baseline 90 and 365).
  - Tie-break: supplier ID ascending.
- The exact formula and weights are your design, but they must be consistent, explainable, and stored as policy parameters.
- Store every score component and the policy parameter values used, and show them in the buyer UI.
- The method must reproduce the three worked-example rankings:
  - ITM-1048: S029 rank 1, S030 rank 2; S031 excluded.
  - ITM-1036: S023 rank 1, S022 rank 2, S024 rank 3.
  - ITM-1016: S009 rank 1, S010 rank 2.

Reference price (6.3):

- The price on that supplier's most recent PO for the item in `preferred_history_days` (baseline 548).
- If none, the rate in that supplier's most recent contract for the item.
- Tie-break: highest `po_number` for same-date POs; for contracts, latest start date, then highest contract ID.

Suggesting suppliers for sourcing (6.4):

- Take the preferred list in order.
- If it has fewer suppliers than needed (3 or 5), add other Active suppliers that list the item's category in the supplier master.
- Order the top-up by `topup_weight_on_time × (on_time_pct / 100) + topup_weight_quality × (quality_rating / 5)`, descending, then supplier ID ascending. Baseline weights are both `0.50`.
  - A strict on-time-first order is wrong: it picks S001 for ITM-1006 and S019 before S017 for ITM-1034, contradicting the worked examples.
- Exclude Blocked/Inactive suppliers and duplicates.
- The preferred list always comes before the category top-up. This is validated by ITM-1071 (S045 first, then S047, S020, S048, S046), ITM-1006 (S003, S002, S005), and ITM-1034 (S020, S017, S019, S018).
- New items get no suggestions.
- If the pool is exhausted, return fewer than requested.

Export top 3:

- Columns: `item_code, rank, supplier_id, reference_price`.
- As of a chosen date.
- Export however many suppliers qualify for the item as of that date. If an item has only one or two, export one or two rows; do not pad with empty rows.

### 7.8 Step 7 — PO consolidation

- Consider only requirements that end in a PO.
- Process those requirements in creation-time order `(first_line_created_at, pr_no, pr_line)`. This is the 7.2 order and is separate from the requirement-date valuation pass.
- A requirement joins the earliest open PO it qualifies for; otherwise it starts a new PO.
- Qualification:
  - Same supplier.
  - Same plant.
  - Same route.
  - Raised within `consolidation_hours` (baseline 24) of the PO's first requirement.
- The consolidation window uses the full creation datetime of each requirement's first line, not midnight of the date.
- Sourcing requests are never combined.
- Generate stable PO IDs and write the same `po_id` to every consolidated line.

### 7.9 Step 8 — Output

Emit one row per PR line in input order using the exact 13-column format in Section 9.

---

## 8. Time, dates, and tie-breaks

- **Requirement date** = date portion of the first line's `created_at`. Used for policy version, contract validity, PO history, and preferred-list as-of date.
- All windows and limits below are read from the selected policy version. Baseline values are in §6.5.
- **Club window** (`club_window_days`, 14) and **need-by window** (`need_by_window_days`, ±7) use calendar dates, inclusive.
- **Consolidation window** (`consolidation_hours`, 24) uses full datetimes, inclusive.
- **Contract start and end dates** are inclusive.
- **Policy `effective_from`** is inclusive.
- **PO history windows**:
  - Valuation (`valuation_history_days`, 365): `po_date >= requirement_date - 365` and `po_date <= requirement_date`.
  - Active history (`active_history_days`, 365): `po_date >= requirement_date - 365` and `po_date <= requirement_date`.
  - Preferred history (`preferred_history_days`, 548): `po_date >= as_of_date - 548` and `po_date <= as_of_date`.
- Use IST consistently. Store UTC if preferred, but convert at the edges.
- **Most recent PO tie-break:** highest `po_number` on the same date.
  - This is a **decided convention**, supported by sequential numbering and the file order in `po_history.csv`, but not proven by a worked example.
  - ITM-1034 does not prove it because the higher-numbered PO is also the higher-priced PO.
  - ITM-1039 is the discriminating case for a requirement dated on or before 2026-10-16: 7300259 is ₹2,967.87 and 7300265 is ₹2,655.92; the higher number has the lower price.
  - Do not use the 2026-09-23 pair for PR-26-00041 (2026-11-06): the later PO 7300264 (2026-10-17, ₹2,893.72) is the most recent and sets that requirement's valuation. The same-date pair is a synthetic tie-break test.
  - Add a synthetic unit test for this convention.
- **Most recent contract tie-break:** latest start date, then highest contract ID.
- **Supplier status** is the current status in the supplier master; there is no status history.
- **Contract applicability** requires the supplier to be Active now.
- **Pre-register requirement dates:** if a requirement predates the register's last update (31 October 2026), the baseline consumed values may include later POs. Flag this in `DECISIONS.md`; the November batch avoids it.
- **Rounding:** use exact unit prices for calculation, round monetary outputs to 2 decimals, and display in INR. Do not round the unit price before multiplying.

---

## 9. CSV output contract

Always emit exactly these 13 columns, on every row:

`pr_no, pr_line, resolved_item_code, group_id, po_id, decision, route, supplier_id, unit_price, group_value_inr, suggested_supplier_ids, policy_version, reason`

Serialization rules:

- `resolved_item_code`: matched code or `NEW`. For `NOT_PROCESSED`, use the supplied valid code when one exists; otherwise leave it empty rather than inventing a match.
- `group_id`: requirement ID; clubbed lines share it. **Empty string** for `NOT_PROCESSED`.
- `po_id`: populated for PO routes; **empty string** for sourcing and `NOT_PROCESSED`.
- `decision`: `PO` or `SOURCING_REQUEST`; **empty string** for `NOT_PROCESSED`.
- `route`: `CONTRACT_PO`, `EMERGENCY_PO`, `SPOT_PO`, `RFQ`, `RFP`, `CAPEX_SOURCING`, `NEW_ITEM_SOURCING`, or `NOT_PROCESSED`.
- `supplier_id` and `unit_price`: populated for PO routes; **empty string** for sourcing and `NOT_PROCESSED`.
- `group_value_inr`: requirement total; **empty string** for `NOT_PROCESSED` and when a new item has no estimate.
- `suggested_supplier_ids`: semicolon-separated ranked IDs for sourcing; **empty string** for PO routes and `NOT_PROCESSED`.
- `policy_version`: selected version label for decided rows. For `NOT_PROCESSED`, populate it if a version was selected; leave it empty only when no version is effective for the requirement date.
- `reason`: for decided rows, include the policy clause, policy version, and the contract or PO relied on. For `NOT_PROCESSED`, state the validation failure or "no policy effective for requirement date"; there is no clause.
- Row order: stable input order.
- Encoding: UTF-8.
- Quote fields containing commas or semicolons.
- Use empty strings, never `null`, `None`, or missing fields.

`NOT_PROCESSED` interpretation:

- Leave `decision` empty.
- Leave `group_id`, `po_id`, `supplier_id`, `unit_price`, `group_value_inr`, and `suggested_supplier_ids` empty.
- Set `route = NOT_PROCESSED`.
- Populate `reason` with the validation failure, for example "unknown plant" or "invalid quantity".
- Record in `DECISIONS.md` that this is an interpretation of the brief, which lists only `PO` and `SOURCING_REQUEST` as decision values and specifies only the route for skipped lines.

---

## 10. Chat intake

Build a slot-filling assistant with guardrails, not a free-form chatbot.

Required slots:

- Item (resolved or NEW).
- Quantity.
- UOM (default from the item).
- Plant (PUN1 or CHK1).
- Need-by date.
- Urgency (Normal or Emergency).
- Requester and department from the session.

Tools the assistant can call:

- `resolve_item`
- `get_item_details`
- `validate_plant`
- `create_pr_draft`
- `submit_pr`
- `describe_policy_change`
- `ask_clarification`

Intents:

- PR intake fills the required slots and creates a PR.
- Policy change detects phrases such as "from December 1, emergency limit is 3 lakh for Chakan", asks for a missing effective date or scope, then calls `describe_policy_change`.
- The policy-change result is a draft only; it must be shown as a plain-language diff and explicitly confirmed before any amendment is created.
- The assistant never approves or activates a policy change.

Behavior:

- Ask only for genuinely missing fields.
- Handle vague items by showing top matches and asking the user to choose.
- If the match is below `item_match_threshold`, present candidates and offer "new item"; ask for an estimated unit price.
- Never fabricate item codes or suppliers.
- Resolve relative dates such as "next Friday" and show the resolved date in the confirmation.
- Support multiple items and multiple lines in one message.
- Confirm a clear summary before creating the PR or policy amendment.
- Validate plant, quantity, date, and UOM before persistence.
- CSV upload uses the same validation and resolution pipeline and shows per-row errors.

Fallbacks:

- If the LLM or Python service is unavailable, PR intake uses a structured form with template questions and the deterministic matcher.
- Policy changes fall back to paste-text or the manual operations editor; no policy decision depends on Python.

Record the AI tools used in `APPROACH.md`.

---

## 11. Buyer workspace

Every screen has one obvious primary action.

Requirements list:

- Value, route, supplier or price, suggested suppliers, policy clause, status, age.
- Filters by plant, route, status, and policy version.

Requirement detail:

- Constituent PR lines.
- Item match method and confidence.
- Group value calculation.
- Applicable contracts, rates, ceiling before and after.
- Preferred supplier list with score components.
- Decision trace and reason.

Primary actions:

- Approve: creates the PO or sourcing event.
- Override: choose an alternate supplier or route; require a written comment; preserve the original decision and audit who and when.

PO view:

- Consolidated lines and the requirements that formed them.

Sourcing view:

- Ranked suggested suppliers and a send RFQ/RFP action.
- Capex approval flag for Capex routes.

Policy console:

- Active version and effective date.
- Upload, paste, or manual entry.
- Plain-language diff.
- Approve and activate.
- Change history.

Contracts:

- Register list, ceiling and consumption view.
- PDF upload and extraction.
- Manual add and edit.

Audit:

- Who did what, when, before and after.

---

## 12. API

Minimum endpoints:

- `POST /api/process-batch` — accepts a PR batch CSV, runs the engine, returns decisions JSON and stores a run.
- `GET /api/runs/{id}/export.csv` — exact 13-column output.
- `GET /api/preferred-suppliers?as_of_date=...` — full list; export top 3 CSV.
- Policy versions:
  - `GET /api/policies`
  - `GET /api/policies/{versionId}`
- Policy amendments:
  - `POST /api/policies/upload` — extract draft operations from a PDF
  - `POST /api/policies/paste` — extract draft operations from pasted text
  - `POST /api/policies/describe` — extract draft operations from chat; never persists
  - `POST /api/policy-amendments` — persist confirmed operations as a draft
  - `GET /api/policy-amendments/{id}`
  - `POST /api/policy-amendments/{id}/validate`
  - `POST /api/policy-amendments/{id}/preview` — plain-language diff
  - `GET /api/policy-amendments/{id}/diff`
  - `POST /api/policy-amendments/{id}/rules`
  - `PATCH /api/policy-amendments/{id}/rules/{ruleKey}`
  - `DELETE /api/policy-amendments/{id}/rules/{ruleKey}`
  - `POST /api/policy-amendments/{id}/rules/{ruleKey}/reorder`
  - `POST /api/policy-amendments/{id}/parameters`
  - `PATCH /api/policy-amendments/{id}/parameters/{key}`
  - `POST /api/policy-amendments/{id}/approve` — create and activate the version
  - `POST /api/policy-amendments/{id}/reject`
  - `POST /api/policy-amendments/{id}/activate` — optional two-step activation

Draft amendments are mutable; policy versions are immutable. `approve` creates and activates atomically by default; `activate` is for the optional two-step mode.
- Contracts:
  - `GET /api/contracts`
  - `POST /api/contracts`
  - `POST /api/contracts/upload`
  - `POST /api/contracts/{id}/extract`
- PR and chat:
  - `POST /api/prs`
  - `POST /api/prs/{id}/submit`
  - `POST /api/chat/messages`
- Audit:
  - `GET /api/audit`
  - `POST /api/decisions/{id}/override`

Make batch processing idempotent using the input hash and policy version. Store input and output for repeatability.

---

## 13. Contracts

- Load the register and contract lines from CSV.
- Ingest all 12 PDFs, including the two PDF-only contracts.
- PDF extraction must read:
  - Contract ID.
  - Supplier ID and name.
  - Start and end dates.
  - Ceiling.
  - Schedule A item codes, descriptions, UOM, and rates.
- Manual add and edit are always available.
- Track consumption separately from the register baseline.
- Contract usability requires:
  - Item coverage.
  - Requirement date within inclusive validity.
  - Supplier Active.
  - Ceiling availability.
- RC-2026-005 is never usable because S004 is Blocked.
- Expired contracts can still qualify a supplier for the preferred list under 6.1(b), but cannot be used for a PO under 4.1.

---

## 14. Testing

### 14.1 Worked examples — golden tests

| # | Input | Expected |
|---|---|---|
| 1 | ITM-1002, 15 NOS, PUN1, 2026-11-04 | `CONTRACT_PO`, S001, ₹9,168.71, value ₹1,37,530.65, PO-0012 |
| 2 | ITM-1037, 11 NOS, CHK1, 2026-11-18 | `CONTRACT_PO`, S021, ₹2,439.01, value ₹26,829.11, PO-0073 (RC-2026-031 PDF-only) |
| 3 | ITM-1022, 210 + 170 = 380 NOS, CHK1, 2026-11-02 | `RFQ`, S014, S013, S016; value ₹2,20,255.60; contract out of ceiling |
| 4 | ITM-1009, 3920 + 690 = 4,610 NOS, PUN1, 2026-11-02/09 | `CONTRACT_PO`, S007, ₹7.06, value ₹32,546.60, PO-0004; lowest-rate contract with room |
| 5 | ITM-1038, 270 + 190 + 300 = 760 LTR, CHK1, 2026-11-02/03/05 | `SPOT_PO`, S024, ₹166.41 (PO 7300244, 2026-10-19), value ₹1,26,471.60, PO-0002 |
| 6 | ITM-1044, PUN1 7,370 and CHK1 6,480; different plants and need-by dates 56 days apart | Not clubbed; two `SPOT_PO`s to S027; PUN1 value ₹1,56,170.30 (PO-0003); CHK1 value ₹1,37,311.20 (PO-0019); see pricing note below |
| 7 | ITM-1035, 250 MTR, PUN1, 2026-11-06, free text | `SPOT_PO`, S023, ₹391.82 (PO 7300225, 2026-03-30), value ₹97,955.00, PO-0024 |
| 8 | ITM-1026, 2,380 NOS, PUN1, 2026-11-06, Emergency | `EMERGENCY_PO`, S014, ₹106.38, value ₹2,53,184.40, PO-0025 |
| 9 | ITM-1066, 5 JOB, PUN1, 2026-11-05, free text | `RFQ`, S043, S044, S042; value ₹3,36,562.15 (5 × ₹67,312.43; PO 7300445, 2026-10-07) |
| 10 | ITM-1006, 220 KG, PUN1, 2026-11-02 | `RFQ`, S003, S002, S005; value ₹80,300.00 (220 × ₹365); blocked contract, no PO history |
| 11 | ITM-1034, 50 NOS, CHK1, 2026-11-04 | `RFP`, S020, S017, S019, S018; value ₹20,70,780.00 (50 × ₹41,415.60); four suggestions because the pool is exhausted |
| 12 | ITM-1071 EOT crane, 4 NOS, PUN1, 2026-11-03 | `CAPEX_SOURCING`, S045, S047, S020, S048, S046; value ₹60,36,704.08 (4 × ₹15,09,176.02) |
| 13 | New item: Drinking water purifier 50 LPH, 1 NOS, PUN1, 2026-11-05 | `NEW_ITEM_SOURCING`; value ₹1,41,100.00; no suggestions |
| 14 | ITM-1009 four lines, PUN1 | Two requirements: R1 ₹32,546.60 and R2 ₹23,227.40; both consolidated into PO-0004 because same supplier, plant, route, and within 24 hours |

Note on example 14: the stated requirement value ₹23,227.40 is the R2 requirement (1,140 + 2,150 units). R1 (3,920 + 690 units) is example 4 at ₹32,546.60. Both share PO-0004.

Displayed-price note: the worked-examples PDF rounds displayed unit prices (for example ₹166 and ₹392). The engine must use the exact PO or contract price, not the rounded display value. The exact source POs are:

- ITM-1038: PO 7300244, 2026-10-19, S024, ₹166.41. The ₹165.92 entry is PO 7300256, 2025-06-08, from S023, and is not the most recent.
- ITM-1035: PO 7300225, 2026-03-30, S023, ₹391.82.
- ITM-1066: PO 7300445, 2026-10-07, S043, ₹67,312.43.
- ITM-1044: S027's reference price is ₹21.59 (PO 7300300, 2026-04-21; the PDF displays ₹22). The valuation price is ₹21.19, from the most recent PO 7300299 (2026-06-19, S028).

Example 6 pricing note: the two prices diverge. S027 is the 1st preferred supplier and its reference price is ₹21.59, so the PO line unit price is ₹21.59. Valuation under 3.1(b) uses the most recent PO for the item, which is S028's ₹21.19. PUN1 value = 7,370 × ₹21.19 = ₹1,56,170.30; CHK1 value = 6,480 × ₹21.19 = ₹1,37,311.20. The PDF's "S027 at ₹22" is the PO price; its "₹1,56,170" is the valuation value. Do not use ₹21.19 as S027's reference price or ₹21.59 as the valuation price.

Lock these PO numbers in the reference-price and valuation tests.

PO-ID note: the worked-examples PDF's PO IDs are one lower than the full-batch values in §14.1. The full sample batch contains PR-26-00174 and PR-26-00175 (ITM-1033, PUN1, created 2026-11-02 08:10 and 08:14). They club into a `SPOT_PO` and sort before example 5, so a sequential full-batch scheme shifts every PDF PO ID by +1: example 5 is PO-0002, example 6 PUN1/CHK1 are PO-0003/PO-0019, example 4/14 are PO-0004, example 1 is PO-0012, example 7 PO-0024, example 8 PO-0025, example 2 PO-0073. The deeper assertion is the consolidation grouping. If you use a different ID scheme, assert that consolidated lines share an ID and that distinct POs have distinct IDs.

### 14.2 Preferred supplier golden tests

- ITM-1048 as of 2026-11-01: S029 rank 1, S030 rank 2; S031 excluded.
- ITM-1036 as of 2026-11-01: S023, S022, S024.
- ITM-1016 as of 2026-11-01: S009, S010.

### 14.3 Concrete edge cases

- ITM-1017: most recent PO is S011 Blocked at ₹1,479.06; Active history exists via S012/S010/S009; valuation uses ₹1,479.06. The two CHK1 lines (PR-26-00069, 121 CYL; PR-26-00091, 261 CYL) club because creation dates are 9 days apart (≤14) and need-by dates are 3 days apart (≤7), producing 382 CYL; value ₹5,65,000.92; 5.4 fails; route `RFQ`. A single 121-unit test would value ₹1,78,966.26 and allow 5.4, which is exactly why the test must use the clubbed requirement.
- ITM-1020: most recent PO is S011 Blocked at ₹212.46; Active history exists via S009; valuation uses ₹212.46. The only valid line is 4,100 NOS (PR-26-00122; PR-26-00172 is zero-quantity and `NOT_PROCESSED`); value ₹8,71,086; 5.4 fails; route `RFQ`.
- ITM-1049: RC-2026-007 covers the item, S029 is Active, and the contract is valid 2026-01-01 to 2026-12-31, so both November lines route `CONTRACT_PO` at ₹191.31: PR-26-00021 (250 NOS) value ₹47,827.50 and PR-26-00159 (120 NOS) value ₹22,957.20. The 365-day Active-history test is moot here. If the contract were unavailable, the Active PO is 2025-11-04; requirements on 2026-11-08 (369 days) and 2026-11-14 (375 days) would fail 5.4, valuation would use the Inactive S031 PO at ₹217.81, and the route would be `RFQ`. Keep that as a synthetic test.
- ITM-1050: most recent PO is S031 Inactive at ₹129.55; Active history exists; 5.4 applies; valuation uses ₹129.55. There is no sample line; use a synthetic requirement below ₹2L.
- ITM-1006: RC-2026-005 excluded because S004 is Blocked; applicable set empty; no PO history; standard price ₹365; value ₹80,300.00; `RFQ`.
- ITM-1014: PR-26-00007 (9,760 NOS) values at the applicable contract rate ₹20.64, not the Blocked S008 PO at ₹23.06, because the applicable set is non-empty even though RC-2026-015 is out of ceiling; value ₹2,01,446.40; 5.4 fails; route `RFQ`.
- ITM-1039: the most recent PO is 7300264 (2026-10-17, S024, ₹2,893.72), so PR-26-00041 (80 NOS) values at ₹2,31,497.60; `RFQ`; RC-2026-031 is not valid until 2026-11-15. Separate synthetic tie-break test: for a requirement dated on or before 2026-10-16, the same-date 2026-09-23 POs are 7300259 (₹2,967.87) and 7300265 (₹2,655.92); the highest-`po_number` convention gives ₹2,655.92. Do not use the 2026-09-23 pair for PR-26-00041.
- ITM-1003: for a requirement dated 2026-04-05 (or if the later PO were absent), same-date POs 7300023 at ₹88.78 (S004 Blocked) and 7300029 at ₹87.88 (S003 Active) would exercise the tie-break; the higher-number convention gives ₹87.88. For PR-26-00012 (2026-11-08), the most recent PO is 7300030 (2026-10-17, ₹85.70), but RC-2025-011 at ₹83.69 is applicable and usable, so rule 5.2 wins. This is a contract-precedence test, not a tie-break test.
- ITM-1039 on or after 2026-11-15: RC-2026-031 at ₹2,746.87 applies; `CONTRACT_PO`.
- ITM-1071: EOT crane qty 4, value ₹60,36,704.08 from PO 7300466 (2026-08-29, S045, ₹15,09,176.02), `CAPEX_SOURCING`.
- ITM-1073: PR-26-00167 line 2 ("Forklift electric 2 ton - urgent", CHK1, 1 NOS) matches ITM-1073; the 2025-08-31 PO is outside 365 days, so value uses the standard price ₹11,50,000; `CAPEX_SOURCING`.
- Emergency, value > ₹5,00,000, no usable contract, no Active history: 5.3 and 5.4 fail; 5.5 or 5.6 decides by value; the emergency flag has no further effect.
- Low-confidence Capex-like description, for example "big crane": below threshold → NEW, not `CAPEX_SOURCING`.

### 14.4 Clause and boundary tests

- 14-day club window inclusive.
- ±7-day need-by window inclusive.
- 24-hour consolidation inclusive, using datetimes.
- Contract validity inclusive.
- Policy effective date inclusive.
- Ceiling boundary: equal to remaining ceiling is allowed.
- Lowest-rate usable contract selection.
- Valuation fallback ladder: usable contract, non-empty no-ceiling, empty applicable set, no contract.
- 5.3/5.4/5.5/5.6 exact boundaries from the boundary table.
- Emergency > ₹5L with no usable contract and no Active history falls through to 5.5/5.6; the emergency flag has no effect.
- 5.1 beats 5.2; 5.2 beats 5.3/5.4.
- Blocked/Inactive exclusion from POs and suggestions.
- Blocked contract exclusion from applicability and valuation fallback.
- Expired contract inclusion in preferred list.
- Suggestion top-up order: preferred list first; then combined `0.5 × (on_time_pct/100) + 0.5 × (quality_rating/5)` descending; then supplier ID ascending.
- Suggestion count may be less than requested.
- New item no estimate → NULL value.
- `NOT_PROCESSED` rows keep 13 columns.

### 14.5 Determinism and E2E

- Run the same batch twice; outputs must be byte-identical.
- Run with the Python service stopped; batch outputs must be identical.
- Upload the sample CSV; verify 186 rows.
- Run the policy amendment fixtures in §14.6, including the Python-down manual path.
- E2E with Python stopped: use the manual policy editor, approve, reprocess, export.
- Validate the exact 13-column CSV header and empty-string serialization.

### 14.6 Policy amendment and versioning tests

These are synthetic fixtures on a copy of v5.0. Do not mutate the seeded baseline.

| ID | Amendment | Requirement | Expected |
|---|---|---|---|
| P1 | `set_parameter spot_limit=100000`, effective 2026-11-10; rule 5.4 already uses `param:spot_limit` | ITM-1038, 760 LTR, CHK1, 2026-11-05 | Unchanged: `SPOT_PO`, value ₹1,26,471.60; earlier date still selects v5.0 |
| P2 | Same as P1 | ITM-1038, 760 LTR, CHK1, 2026-11-18 | `RFQ`; baseline would be `SPOT_PO`; this proves `set_parameter` changed rule 5.4 |
| P3 | Add rule with `scope_json={"plant":"CHK1"}` and condition `urgency=Emergency` AND `value_inr>=400000` before 5.3 → `RFP` | ITM-1026, 4,000 NOS, CHK1, 2026-11-18, value ₹4,25,520.00 | `RFP`; normalized rule stores a leading `plant=CHK1` leaf in `condition_json`; same requirement at PUN1 remains `EMERGENCY_PO` |
| P4 | Reorder 5.4 before 5.3 | Emergency requirement, value ₹1,50,000, Active history | `SPOT_PO`; baseline would be `EMERGENCY_PO` |
| P5 | `set_parameter spot_limit=100000` scoped to `{"category":"Safety & PPE"}`; rule 5.4 resolves the scoped value for the requirement | ITM-1050, 1,200 NOS, PUN1, 2026-11-18, value ₹1,55,460.00 | `RFQ`; baseline would be `SPOT_PO`; a non-Safety item still gets global 200000 |
| P6 | Re-run P1/P2 through the manual editor instead of extraction | Same as P1/P2 | Same diff, version hash, decisions, and CSV bytes |
| P7 | Invalid drafts: unknown fact, unknown parameter, leaf with both `value` and `param`, invalid route, duplicate priority, missing effective date, Blocked-supplier outcome | Any draft | 4xx validation error; no version or activation created |
| P8 | Two versions with the same `effective_from`; separately, no version effective for a date | Synthetic requirement | Tie-break by `approved_at` then `id`; no-version row is `NOT_PROCESSED` with "no policy effective for requirement date" |
| P9 | Chat: "from 1 Dec, spot limit is 1 lakh" | Chat session | Returns draft operations and diff; does not approve or activate; after explicit confirm, approval, and activation, eligible dates change |
| P10 | Reprocess the sample batch after P1 | Sample batch | Rows on/after 2026-11-10 use the new version in `policy_version` and reason; earlier rows keep v5.0 |

---

## 15. Deployment and README

Local run:

- `docker compose up` starts web, Spring Boot API, Python AI service, and PostgreSQL.
- Flyway runs migrations on startup.
- Seed data is idempotent: items, item aliases, suppliers, plants, PO history, contracts, policy facts (including `need_by_offset_days` and `has_active_history`), and policy v5.0 rules with `param` references.
- Health checks for all services.

Hosted:

- Deploy to Render or Railway with three services plus managed PostgreSQL.
- Set environment variables for database URL, AI provider key, service token, and public base URL.
- Keep the app warm for the blind-test window; free tiers may sleep.
- Provide the hosted link in the README.

README contents:

- Prerequisites.
- Environment variables.
- One-command run.
- Seed and reset instructions.
- Hosted link.
- API examples.
- Spring Boot + Python split.
- AI tools used.
- Manual policy amendment fallback steps.

---

## 16. Blind-test runbook

Before the deadline:

- Freeze code and verify the hosted app is live.
- Confirm PostgreSQL is seeded and policy v5.0 is approved and active.
- Confirm the Spring Boot and Python health endpoints.
- Rehearse the full run twice.

At the blind test:

1. Receive the new PR batch and policy amendment.
2. **Run 1:** process the batch under v5.0 and export the decisions CSV.
3. **Amendment:** try PDF upload. If extraction fails or is slow, switch to paste-text or the manual editor. Enter the changes, validate, preview the diff, set the effective date, then approve-and-activate. Time-box to about 10 minutes.
4. **Run 2:** reprocess the same batch and export the decisions CSV.
5. Export the preferred supplier lists as of the given date, top 3.
6. Push exactly three CSVs into the `submission/` folder.
7. Do not change code.

Pre-written manual amendment checklist:

- Open Policy Console.
- Choose Manual Editor or Paste Text.
- Enter changed limits, rules, scopes, and effective date.
- Validate.
- Review the plain-language diff.
- Approve and activate.
- Reprocess the batch.
- Export.

---

## 17. DECISIONS.md checklist

Record each of these with "what I noticed, what I decided, why":

1. Item matching method, threshold, alias lexicon, and below-threshold handling.
2. Below-threshold Capex near-miss is treated as NEW, not `CAPEX_SOURCING`.
3. Invalid item code: fall back to description; log the discrepancy.
4. Code vs description conflict: code wins.
5. Invalid plant, for example PUN2: `NOT_PROCESSED`.
6. Zero or negative quantity: `NOT_PROCESSED`.
7. UOM mismatch: `NOT_PROCESSED` or manual review; no conversion table.
8. Missing need-by date: hold or `NOT_PROCESSED`; chat asks.
9. New item with no estimate: NULL value, `NEW_ITEM_SOURCING`, empty `group_value_inr`.
10. New item always `NEW_ITEM_SOURCING`, even if Capex-like; 1.2 overrides 5.1.
11. New items are never clubbed.
12. Duplicate PR lines are processed separately.
13. 3.1(b) valuation is status-agnostic: Blocked/Inactive PO prices may be used.
14. 5.3/5.4 Active-supplier test is separate: Blocked/Inactive PO history does not count.
15. Blocked contract is excluded from applicability and from the valuation fallback.
16. Expired contract still qualifies a supplier for the preferred list under 6.1(b).
17. Lowest-rate usable contract selection; non-empty no-ceiling case; empty applicable set case.
18. Capex and contract: no contract is "used" when 5.1 wins; value via PO history or standard price.
19. Ceiling consumption is run-scoped and recomputed from the baseline; repeat runs are deterministic.
20. Pre-register requirement dates may have inaccurate baseline consumption; flag.
21. Highest `po_number` is the same-date "most recent PO" convention; not data-verified, documented as a decision.
22. ITM-1003 is a contract-precedence case, not a tie-break case.
23. Most recent contract tie-break: latest start date, then highest contract ID.
24. Supplier status is current; no status history exists.
25. Recency buckets: 90 days, 365 days, older.
26. Ranking weights and formula; explainability.
27. Category top-up order: combined `0.5 × (on_time_pct/100) + 0.5 × (quality_rating/5)` descending, then supplier ID ascending. A strict on-time-first order contradicts the worked examples (ITM-1006, ITM-1034).
28. Preferred list comes before category top-up.
29. Suggestion count may be less than requested when the pool is exhausted.
30. PO consolidation uses datetimes and inclusive 24 hours; PO ID format is implementation-defined. The PDF IDs are illustrative: PR-26-00174/175 sort before example 5, so a sequential full-batch scheme shifts the PDF IDs by +1.
31. Emergency is requirement-level if any line is Emergency.
32. 5.2 beats emergency; 5.1 beats contract.
33. `NOT_PROCESSED` leaves `decision` empty; route carries `NOT_PROCESSED`; reason explains. This is an interpretation. Also leave `group_id`, `po_id`, supplier/price, value, and suggestions empty.
34. CSV blank fields are empty strings, never null.
35. Rounding: exact unit prices for calculation; 2-decimal monetary output.
36. Manual policy amendment fallback and its use in the blind test.
37. Chat relative-date resolution against a defined "today".
38. Free-text noise handling and aliases.
39. Multi-line PR handling.
40. Service and monthly UOM handling.
41. Emergency flag has no special effect when value > ₹5,00,000 and no usable contract or Active history exists; 5.5/5.6 apply normally.
42. Preferred-supplier export includes only qualifying suppliers; no padding to three rows.
43. Worked-example displayed prices are rounded; the engine uses exact PO/contract prices (ITM-1038 PO 7300244 at ₹166.41; ITM-1035 PO 7300225 at ₹391.82; ITM-1066 PO 7300445 at ₹67,312.43). ITM-1044 is the exception: the PO line price is S027's ₹21.59, while the valuation price is S028's ₹21.19; the PDF shows ₹22 and value ₹1,56,170.
44. Within a run, requirements are processed in `(requirement_date, first_line_created_at, pr_no, pr_line)` order, and the in-memory ceiling ledger is updated after each contract-consuming requirement.
45. Preferred-list ranking formula: `0.40 × recency + 0.25 × normalized 548-day PO count + 0.20 × contract bonus + 0.10 × on-time + 0.05 × quality`; tie-break supplier ID ascending.
46. The preferred list is computed before rules 5.3/5.4 need it; step 6 materializes the full list and export.
47. Duplicate PR rows are not deduplicated, but distinct PR lines for the same item/plant still club under 2.2; PR-26-00174/175 are one requirement.
48. Policy version selection is by requirement date, with tie-break `effective_from` descending, then `approved_at` descending, then `id` descending; no effective version is `NOT_PROCESSED`.
49. `SCHEDULED` versions are activated but future-dated; they become selectable when the requirement date reaches `effective_from`.
50. New rule combinations, scopes, thresholds, and outcomes using registered facts require no code. A genuinely new fact needs a resolver and a `policy_facts` row; this boundary is deliberate.
51. Policy-change chat only creates a draft amendment after explicit confirmation; it never approves or activates.
52. Ranking weights, top-up weights, item-match weights, and item-match threshold are policy parameters; the baseline values reproduce the worked examples.
53. Manual editor and extraction use the same operation model, validation, diff, and activation APIs; the manual path is not reduced-functionality.
54. Invalid policy drafts are rejected atomically: unknown fact, invalid route, duplicate priority, missing effective date, or Blocked-supplier outcome creates no version.
55. The policy amendment tests in §14.6 are part of acceptance, including the Python-down manual path and a same-effective-date tie-break.
56. Contract validity, ceiling arithmetic, and Blocked/Inactive supplier exclusion are engine invariants. Policy rows can reference their facts but cannot override them.
57. Seeded rule conditions reference policy limits through `param` leaves; duplicate numeric literals for `emergency_limit`, `spot_limit`, `rfq_rfp_boundary`, or `active_history_days` are not allowed. `set_parameter` must change decisions without a rule edit.
58. Parameter resolution takes `RequirementFacts` and applies `item_code` > `category + plant` > `plant` > `category` > global; it never looks up by version and key alone.
59. `scope_json` is metadata only. Materialized rules store one normalized `condition_json` with scope merged as a leading `all`; the evaluator has no separate scope path, and `update_rule` replaces the leading scope leaves rather than appending them.
60. `need_by_offset_days` is seeded as `need_by_date_minus_requirement_date`; `has_active_history` is computed from the version's `active_history_days`.

---

## 18. APPROACH.md notes

State:

- The stack: React/Next, Spring Boot, Python FastAPI, PostgreSQL, Docker Compose.
- Why the deterministic engine is in Spring Boot and the AI/document layer is in Python.
- That Batch processing never depends on Python.
- That Python returns proposals only and never makes procurement decisions.
- AI tools used for coding and inside the app.
- The supplier ranking formula and weights.
- The policy-as-data model, facts registry, version selection, and amendment workflow.
- The no-code boundary: new rule combinations versus a genuinely new fact.
- The manual fallback and why it exists.
- The test strategy and the worked-example golden tests.
- The host link and one-command run.

---

## 19. Three-day build plan

Day 1:

- Spring Boot scaffold, PostgreSQL, Flyway, seed importers.
- Item resolution with aliases and threshold.
- Policy v5.0 stored as data; facts registry, rule and parameter schema.
- Clubbing, valuation, contract applicability, ceiling ledger.
- Decision rules and PO consolidation.
- CSV export.
- No Python dependency for this path.

Day 2:

- Preferred supplier ranking, explanations, top-3 export.
- Buyer workspace and requirement detail.
- Chat intake through Python with slot filling and guardrails.
- Policy upload, paste, chat draft, diff, approve, activate; manual editor.
- Contract PDF extraction and manual add.
- API endpoints.

Day 3:

- Golden tests against the 14 worked examples.
- Concrete edge-case tests.
- Boundary and determinism tests.
- Deployment and hosted link.
- README, APPROACH.md, DECISIONS.md.
- Blind-test rehearsal, including the Python-down manual amendment path.

---

## 20. Pitfalls to avoid

- Hard-coding policy limits, rule order, or PR answers.
- Seeding rule conditions with numeric literals while also storing those limits as parameters, so `set_parameter` becomes a no-op.
- Resolving parameters by version and key only, without the requirement's facts, so scoped parameters never apply.
- Evaluating `scope_json` as a separate path while the amendment processor merges it into `condition_json`.
- Using today's date instead of the requirement date for policy, contracts, or history.
- Letting Blocked/Inactive suppliers into POs or suggestions.
- Using RC-2026-005 despite S004 being Blocked.
- Using an expired contract for a PO while still including it in the preferred list.
- Applying value limits per PR line instead of per requirement.
- Wrong valuation fallback order or circular ceiling logic.
- Treating PDF-only contracts as available before their inclusive start dates.
- Confusing "≤ ₹5L" emergency with "< ₹2L" spot.
- Requiring an Active supplier for 3.1(b) valuation.
- Allowing a Blocked/Inactive PO to satisfy 5.3/5.4.
- Letting Python decide batch item matches.
- Non-deterministic sorting, tie-breaks, or IDs.
- Persisting mid-run ceiling consumption.
- Forgetting empty strings in the CSV.
- Forgetting the manual policy amendment fallback.
- Treating an `APPROVED` two-step version as effective before activation.
- Selecting policy by today's date instead of requirement date.
- Undefined behavior when two versions share an effective date or no version applies.
- Assuming every new policy fact works without a resolver; document the no-code boundary.
- Letting a policy rule override the Blocked/Inactive supplier guardrail.
- Auto-approving or auto-activating a policy change described in chat.
- Building the manual editor as a reduced-functionality path.
- Trusting the ITM-1034 example as proof of the PO tie-break; use ITM-1039 and a synthetic test.
- Using the 2026-09-23 ITM-1039 pair for PR-26-00041; the 2026-10-17 PO is more recent.
- Treating ITM-1003 as a tie-break test when the contract always wins.
- Misclassifying EOT crane and Forklift as new items.
- Reporting fewer than the requested suggestions without checking the category pool.
- Using a strict on-time-first category top-up; the worked examples require a combined delivery+quality score.
- Using ITM-1049 to test the 365-day rule while ignoring RC-2026-007; the contract applies.
- Confusing the 1st preferred supplier's reference price with the valuation price (ITM-1044).
- Assuming the worked-example PO IDs are literal for the full batch; PR-26-00174/175 sort earlier and shift sequential IDs.
- Making the blind test depend on Python for batch processing or policy activation.

---

## 21. Final acceptance checklist

- [ ] `docker compose up` runs the full stack with seeded data.
- [ ] Policy v5.0 is active and stored as data, with rule rows, scoped parameters, and the `policy_facts` registry seeded.
- [ ] Every v5.0 rule condition that uses a policy limit references it through `param`, and `set_parameter` changes decisions without editing a rule.
- [ ] The parameter resolver receives `RequirementFacts` and applies the full specificity order; P5 resolves the Safety & PPE value.
- [ ] Rule scope is normalized into `condition_json`; the evaluator has no separate scope path, and P3 stores the CHK1 leaf.
- [ ] `need_by_offset_days` is seeded with the `need_by_date_minus_requirement_date` resolver.
- [ ] Sample CSV processes to 186 output rows: 184 decided rows and 2 `NOT_PROCESSED` rows, exactly PR-26-00172 (zero quantity) and PR-26-00173 (PUN2).
- [ ] All 14 worked examples match route, supplier, exact price, value, consolidation grouping, and the full-batch PO IDs in §14.1. If you choose a different ID scheme, assert grouping and ID uniqueness instead; the PDF's PO IDs are one lower because PR-26-00174/175 sort first.
- [ ] Preferred supplier lists match the three worked examples using the documented ranking formula.
- [ ] Category top-up matches ITM-1006, ITM-1034, and ITM-1071 using the combined delivery+quality score.
- [ ] Edge assertions pass: ITM-1017 clubbed value ₹5,65,000.92; ITM-1049 `CONTRACT_PO` at ₹191.31; ITM-1039 value ₹2,31,497.60; ITM-1014 value ₹2,01,446.40.
- [ ] CSV has exactly 13 columns and correct empty strings.
- [ ] Repeat runs are byte-identical.
- [ ] Batch run with Python stopped is byte-identical.
- [ ] Policy amendment can be uploaded, pasted, described in chat, diffed, approved, and activated.
- [ ] Chat policy change requires explicit confirmation and never auto-approves.
- [ ] Manual policy editor completes the same amendment without Python with the same diff and version hash.
- [ ] Policy drafts reject unknown facts, unknown parameters, leaves with both `value` and `param`, invalid routes, duplicate priorities, missing effective dates, and Blocked-supplier outcomes.
- [ ] Policy selection handles `SCHEDULED`, same-effective-date tie-break, and no-version fallback.
- [ ] §14.6 policy amendment fixtures pass, including the Python-down manual path.
- [ ] Reprocessing after an amendment changes only eligible requirement dates.
- [ ] Buyer can approve and override with a comment; audit records both.
- [ ] Contracts register and PDF-only contracts are loaded.
- [ ] Hosted link is live.
- [ ] README, APPROACH.md, and DECISIONS.md are complete.
- [ ] No hard-coded PR numbers or item answers.
- [ ] Blind-test submission folder and runbook are ready.

---

## 22. As-built implementation notes

These notes record deliberate implementation choices that differ from the original plan but preserve the required
behaviour.

- **Chat intake.** Chat uses a deterministic Java regex slot-filler rather than the Python/LLM architecture described
  in the original plan. The Python AI service exists and is wired, but it is not used for chat. This keeps the
  blind-test critical path independent of LLM availability. The functional requirements are met: slot filling, item
  resolution, policy-change detection, and no auto-approve.
- **AI resilience.** The AI client has configurable timeouts and retries. A full circuit breaker (for example
  Resilience4j) was not implemented; the batch decision path is fully independent of the AI service and is unaffected
  by AI unavailability.
- **Runtime stack.** The backend runs Spring Boot 3.3 on Java 17, and the frontend uses React 18 with Vite. Both fit
  the brief's "Spring Boot 3.x" and "React/Next.js (or similar)" allowances.
