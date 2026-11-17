# Corvo Procurement — PR to PO

Deterministic PR-to-PO decisioning with a policy-as-data console, buyer chat, contract extraction, preferred
supplier ranking, buyer approvals, audit trail, and CSV exports. The Spring Boot engine owns every decision; the Python service
only proposes extractions and chat slots.

## Stack

| Layer | Technology |
| --- | --- |
| Web | React 18 + Vite, nginx in Docker |
| API / engine | Spring Boot 3.3.5, Java 17 |
| AI / documents | Python 3.12, FastAPI, pypdf |
| Database | PostgreSQL 16 in Docker; H2 (PostgreSQL mode) for local dev |
| Migrations | Flyway |
| Orchestration | Docker Compose |

## Prerequisites

- Docker Desktop with Compose v2 (for the one-command path).
- For local development without Docker: JDK 17+, Maven 3.9+, Node 20+, Python 3.12+.

## One-command run

```bash
docker compose up --build
```

| Service | URL |
| --- | --- |
| Web UI | http://localhost:5173 |
| Spring Boot API | http://localhost:8080 |
| Python AI service | http://localhost:8000 |
| PostgreSQL | localhost:5433 (`corvo` / `corvo`) |

Flyway migrates the schema and the idempotent seed runs on backend startup. Health checks:
`http://localhost:8080/actuator/health` and `http://localhost:8000/health`.

Reset the database and reseed:

```bash
docker compose down -v
docker compose up --build
```

## Local development without Docker

```bash
# backend (H2 in-memory, seeded on startup)
cd backend
mvn spring-boot:run

# AI service
cd ai-service
python -m pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000

# web
cd frontend
npm install
npm run dev
```

Vite proxies `/api` to `http://localhost:8080`.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | H2 in-memory | JDBC URL; Compose sets `jdbc:postgresql://db:5432/corvo` |
| `SPRING_DATASOURCE_USERNAME` | `sa` | Database user (`corvo` in Compose) |
| `SPRING_DATASOURCE_PASSWORD` | empty | Database password (`corvo` in Compose) |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `org.h2.Driver` | `org.postgresql.Driver` in Compose |
| `CORVO_AI_BASE_URL` | `http://localhost:8000` | Python service base URL |
| `CORVO_AI_TOKEN` | `local-dev-token` | Shared bearer token for Python calls |
| `CORVO_SEED_ENABLED` | `true` | Idempotent seed toggle |
| `PORT` | `8080` | Spring Boot port |

## Hosted link

Temporary live demo backed by the local Docker Compose stack (Cloudflare quick tunnel; available while the stack
and tunnel process are running):

```
Hosted app: https://suggestions-trademark-essentially-conviction.trycloudflare.com
```

The repository is deployment-ready for a persistent host: deploy the backend, AI service, and PostgreSQL, then
deploy the frontend with `VITE`-less relative `/api` calls (nginx config included). Set the environment variables
above and replace the temporary URL with the persistent hosted URL.

## Seed data and determinism

- Seed sources are the task-kit CSVs copied to `backend/src/main/resources/seed/` (`items.csv`, `suppliers.csv`,
  `sample_pr_batch.csv`) plus the task-kit PO/contract history.
- Policy v5.0 is seeded as `APPROVED`/`ACTIVE`, effective 2026-04-01, with 13 facts, 24 parameters, and 7 rules.
  All limit conditions reference `param` leaves.
- The sample batch produces 186 line rows, 149 requirements, 149 decisions, and 82 POs, with exactly two
  `NOT_PROCESSED` lines: PR-26-00172 (invalid quantity) and PR-26-00173 (unknown plant).
- Batch runs are idempotent by `run_key` (input hash + latest activated policy). Repeat runs return the stored
  result; contract consumption is recomputed from the immutable baseline, never from previous runs.

## Buyer workspace and approvals

Open **Requirements** to filter by plant, route, status, policy, or free text. Expand a row to see its PR lines,
applicable contract headroom, preferred suppliers with score components, and the stored policy trace. **Approve**
sets the decision to `APPROVED`; a PO becomes `APPROVED` only when all of its requirements are approved.
Override remains available with an audit comment. **Purchase orders** and **Sourcing** show the resulting states;
send an approved RFQ/RFP to move it from `APPROVED` to `SENT`. **Manual PR** is the fallback intake when chat or CSV
upload is unavailable, with per-row validation errors and engine results.

Contracts can be added or edited manually in **Contracts** (header plus rate lines), and **Preferred suppliers**
shows score, recency, PO count, contract coverage, on-time, quality, reference price, and reference source.

## API examples

Process the bundled sample batch:

```bash
curl -X POST http://localhost:8080/api/process-batch/sample
```

Upload a PR CSV:

```bash
curl -F "file=@sample_pr_batch.csv" -F "uploadedBy=buyer" \
  http://localhost:8080/api/process-batch
```

Export decisions for run 1:

```bash
curl -o decisions.csv http://localhost:8080/api/runs/1/export.csv
```

Preferred suppliers as of 1 November 2026:

```bash
curl "http://localhost:8080/api/preferred-suppliers?as_of_date=2026-11-01&item_code=ITM-1036"
curl -o preferred.csv "http://localhost:8080/api/preferred-suppliers/export.csv?as_of_date=2026-11-01"
```

Create and inspect a policy amendment:

```bash
curl -X POST http://localhost:8080/api/policy-amendments \
  -H "Content-Type: application/json" \
  -d '{"effective_from":"2026-12-01","source":"manual","created_by":"procurement",
       "operations":[{"type":"set_parameter","key":"spot_limit","value":100000}]}'

curl -X POST http://localhost:8080/api/policy-amendments/1/preview
curl -X POST http://localhost:8080/api/policy-amendments/1/approve \
  -H "Content-Type: application/json" -d '{"actor":"procurement"}'
```

Chat:

```bash
curl -X POST http://localhost:8080/api/chat/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"Need 20 hex bolt M10x40 for Pune by 25 Nov","today":"2026-11-16"}'
```

## Spring Boot + Python split

- Spring Boot: catalogue, PRs, requirements, contract/ceiling arithmetic, policy version selection, rule
  evaluation, preferred lists, PO grouping, audit log, CSV export, and all state transitions.
- Python FastAPI: `POST /policy/describe`, `/policy/extract`, `/policy/upload`, `/contract/extract`,
  `/chat/parse`. It returns proposals only. The deterministic Python parsers handle the provided policy and
  contract PDFs; the endpoints can be backed by an LLM without changing the Java contract.
- Batch processing never calls Python. AI failures surface as `503` on AI-only endpoints.

## Manual policy amendment fallback

1. Open **Policy console**.
2. Create a draft amendment: set the effective date and edit the operations JSON, or select a chat-created draft.
3. **Validate**, then **Preview** to see the plain-language diff and content hash.
4. **Approve & apply** (or approve then **Activate** for a scheduled version).
5. Reprocess the batch and export.

The same path works if the Python service is down. Unknown facts, invalid routes, duplicate priorities, missing
effective dates, and Blocked-supplier outcomes reject atomically with no partial version.

## Tests

```bash
cd backend
mvn test
```

Coverage includes the 14 golden worked examples, all three preferred-supplier lists, policy amendments (including
the Python-down manual path and same-date tie-break), chat flow, deterministic repeat runs, buyer approval/PO
promotion, sourcing send, and workspace projections. Tests are transactional and roll back so golden fixtures are
not contaminated.

## Repository layout

```
backend/        Spring Boot API and deterministic engine
ai-service/     FastAPI extraction and chat proposer
frontend/       React/Vite buyer workspace
deploy/         Dockerfiles and nginx config
docs/           API reference and blind-test runbook
docker-compose.yml
DECISIONS.md    Section 17 checklist and deviations
APPROACH.md     Original implementation blueprint
```

## Blind test

Use `docs/RUNBOOK.md`. It covers Run 1, the PDF/paste/manual amendment path, Run 2, preferred-supplier
export, and the exact-three-CSV final check. The stack must not need Python for batch processing or policy
activation.

## AI tools used

- OpenAI Codex/opencode-style coding agents were used to scaffold, implement, and test the stack.
- Inside the app, the Python layer is deterministic today. It is deliberately swappable for an LLM provider for
  policy/contract extraction and chat parsing; no API key is required to run this submission.

## Notes

- There is no authentication in this build; `requester`, `department`, and `actor` are trusted fields from the UI.
  Production would add SSO and role enforcement.
- `contract_consumption_runs` is audit-only. Contract ceilings always start from the seeded baseline.
- Currency in export/CSV is numeric INR; reasons use plain text (`Rs`) to avoid console encoding issues.
