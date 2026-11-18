# Corvo Procurement - Application Guide

This guide explains every page, feature, button, API call, and output in the Corvo PR-to-PO application. It is written for demo preparation, blind-test operation, and handover.

## 1. What the application does

Corvo converts purchase requisition (PR) lines into deterministic procurement decisions:

- `PO` decisions create proposed purchase orders with a supplier, unit price, and value.
- `SOURCING_REQUEST` decisions create RFQ, RFP, CAPEX, or new-item sourcing events.
- Invalid lines become `NOT_PROCESSED`.

The Spring Boot engine owns all business rules. The React frontend only displays data and sends actions. The Python AI service only proposes policy/contract extractions and chat slots; it does not approve or activate policy.

The application is seeded with:

- Policy `v5.0`, active from `2026-04-01`
- 7 policy rules
- 24 policy parameters
- 13 policy facts
- 12 rate contracts
- Item, supplier, PO history, and contract data
- The sample PR batch containing 186 lines

The sample batch produces:

- 186 output rows
- 149 requirements
- 149 decisions
- 82 proposed POs
- 2 `NOT_PROCESSED` lines: `PR-26-00172` and `PR-26-00173`

## 2. Global layout

### Sidebar

The left sidebar contains the application name and 10 navigation buttons.

| Button | Opens |
| --- | --- |
| `Buyer workspace` | Batch processing, run history, decision export, line-level override |
| `Requirements` | Requirement-level approvals, details, contracts, preferred suppliers, policy trace |
| `Purchase orders` | Proposed/approved POs and their requirements |
| `Sourcing` | RFQ/RFP/CAPEX events and send actions |
| `Manual PR` | Form-based PR entry fallback |
| `Buyer chat` | Conversational PR and policy intake |
| `Policy console` | Policy versions, amendments, validation, preview, approval, activation |
| `Contracts` | Contract register, manual contract editing, PDF extraction |
| `Preferred suppliers` | Ranked supplier lists and top-three CSV export |
| `Audit trail` | Recorded actions with before/after values |

Only one page is visible at a time. Switching pages does not change backend state. Some pages load their data automatically when first opened.

### Status badges

Badges use color to indicate state:

- Green: `READY_FOR_PO`, `PROCEED`, `ACTIVE`, `APPLIED`, `VALIDATED`, `Active`, `CONTRACT`, `APPROVED`, `SENT`
- Red: `BLOCKED`, `NOT_PROCESSED`, `REJECTED`, `INACTIVE`, `Invalid`
- Amber: `PENDING`, `DRAFT`, `SCHEDULED`, `SPOT`, `RFQ`, `PREFERRED`, `NEW_ITEM`, `PROPOSED`, `RFP`
- Neutral gray: other values or empty values

### Errors and notices

- Red error boxes show failed API calls or validation failures.
- Green notice boxes show successful actions and state changes.
- Warnings explain non-fatal issues such as extraction warnings or partially processed rows.

## 3. Buyer workspace

**Purpose:** Process a PR batch, inspect every line-level decision, find runs, override a decision, and download the decisions CSV.

### Main actions

| Control | What it does |
| --- | --- |
| `Process sample batch` | Calls `POST /api/process-batch/sample`. Processes the bundled 186-line sample. Loads the result, stores a run, and reloads run history. |
| `Upload PR CSV` | Opens a CSV file picker. Uploads the selected file to `POST /api/process-batch` as multipart form data. Processes it under the currently active policy. |
| `Download decisions CSV` | Downloads `/api/runs/{runId}/export.csv` for the open run. |

### Statistics

After a run is processed or opened:

| Stat | Meaning |
| --- | --- |
| `Run` | Active run number |
| `PR lines` | Number of input/output lines |
| `Requirements` | Number of grouped requirements |
| `POs proposed` | Count of distinct non-empty PO IDs in the result |
| `Not processed` | Lines with route or decision `NOT_PROCESSED` |

The policy version used by the first line is also shown next to the download link.

### Runs panel

Lists all stored runs, newest according to backend order.

- Each run button opens that run.
- The selected run is highlighted.
- A run entry shows the run number, filename, policy version, and creation time.
- Clicking a run loads both the run result and the stored decisions for override support.
- Empty state: `No runs yet. Process the sample batch.`

Run processing is idempotent by run key: the same input file and active policy return the existing run instead of creating a duplicate.

### Decisions table

Columns:

| Column | Meaning |
| --- | --- |
| `PR` | PR number and line number |
| `Item` | Resolved item code |
| `Decision` | `PO`, `SOURCING_REQUEST`, or `NOT_PROCESSED` |
| `Route` | `CONTRACT_PO`, `EMERGENCY_PO`, `SPOT_PO`, `RFQ`, `RFP`, `CAPEX_SOURCING`, `NEW_ITEM_SOURCING`, or `NOT_PROCESSED` |
| `Clause` | Policy clause that matched |
| `Supplier` | Selected supplier; shows an override marker when overridden |
| `Unit price` | Selected unit price in INR |
| `Value` | Group value in INR |
| `PO` | Proposed PO ID |
| `Reason` | Human-readable policy reason |
| `Override` | Opens the override prompts |

### Filter box

`Filter PR, item, supplier, PO...` is a client-side text filter over:

- PR number
- Resolved item code
- Decision
- Route
- Supplier ID
- PO ID
- Reason

No API call is made. `No lines match the filter.` appears when nothing matches.

### Override button

Only shown when a stored decision exists for the line. It opens four sequential browser prompts:

1. `Override route`
2. `Override to which Active supplier ID?` for PO routes
3. `Override unit price` for PO routes
4. `Override comment` which is required for the audit trail

For PO routes, supplier and unit price are mandatory. The request is sent to `POST /api/decisions/{id}/override` with:

```json
{
  "route": "SPOT_PO",
  "supplier_id": "S023",
  "unit_price": 100.0,
  "comment": "Buyer override reason",
  "actor": "buyer"
}
```

On success, the decision is replaced in the visible list and a notice confirms the new route. The backend recalculates values, creates or removes POs/sourcing events as needed, and records audit history. Canceling any prompt cancels the override.

## 4. Requirements

**Purpose:** Work at requirement level. Approve decisions, override routes, inspect grouped PR lines, contract headroom, preferred suppliers, and policy trace.

### Header controls

| Control | What it does |
| --- | --- |
| `Select a run` dropdown | Switches the run. Defaults to the first run returned by `/api/runs`. |
| `Export CSV` | Downloads `/api/runs/{runId}/export.csv`. |

The page automatically loads runs and then loads the workspace for the selected run.

### Filters

| Filter | Meaning |
| --- | --- |
| `Plant` | Exact plant code match: `PUN1`, `CHK1`, etc. |
| `Route` | Exact route match |
| `Status` | Exact decision status match, defaulting empty to `NOT_PROCESSED` |
| `Policy` | Exact policy version match |
| `Search` | Case-insensitive search over item, plant, route, supplier, decision ID, reason |
| `Clear` | Resets all five filters |

### Requirements table

| Column | Meaning |
| --- | --- |
| `Requirement` | Item code button plus `REQ-{id}` and quantity |
| `Plant` | Requirement plant |
| `Value` | Decision value in INR |
| `Route` | Route badge |
| `Supplier` | Selected supplier |
| `Suggestions` | First three suggested suppliers |
| `Contracts` | Count of applicable contracts |
| `Policy` | Policy version used |
| `Status` | `PROPOSED`, `APPROVED`, etc. |
| `Age` | Age in days |
| `Actions` | `Approve` and/or `Override` |

### Expand requirement

Clicking the item code toggles the detail row. It contains:

1. **PR lines**
   - PR line number
   - Plant
   - Quantity
   - UOM
   - Match method and match score
   - Validation status
2. **Decision**
   - Clause
   - Group value
   - Reference source
   - Capex approval requirement when applicable
   - Calculation: quantity x unit price = value
   - Policy reason
   - Approval notice with actor and timestamp when approved
3. **Applicable contracts**
   - Contract ID
   - Supplier
   - Rate
   - Value cap
   - Consumed before
   - Consumed after
   - Remaining after
   - Usable flag
4. **Preferred suppliers**
   - Rank
   - Supplier ID
   - Score
   - Reference price
   - Reference source
   - Contract flag
   - PO count
   - Recency bucket
   - Raw score components JSON
5. **Policy trace**
   - Collapsible JSON with the complete engine trace

When no contracts, preferred suppliers, or matches exist, the page displays an explicit empty message.

### Approve button

Shown only when the requirement has a decision and status is `PROPOSED`.

- Calls `POST /api/decisions/{decisionId}/approve` with `actor: "procurement"`.
- For a PO route, the first approved requirement creates the PO and its PO lines.
- Additional approvals add lines to the same PO.
- The PO becomes `APPROVED` only when all requirements on it are approved.
- For sourcing routes, approval moves the sourcing event from `PENDING` to `APPROVED`.
- The page refreshes automatically after approval.

### Override button

Calls the same override API as the workspace, using `actor: "procurement"`. It prompts for route, supplier, unit price, and comment as needed. The default comment is `Buyer override` on this page, but the comment field must not be canceled.

### Not processed section

Lists validation failures for the run:

| Column | Meaning |
| --- | --- |
| `PR line` | PR number and line |
| `Item` | Item code if known |
| `Validation` | Validation status |
| `Reason` | Why the line was skipped |

Empty state: `None in this run.`

## 5. Purchase orders

**Purpose:** Review POs proposed by approved requirements.

### Header controls

| Control | What it does |
| --- | --- |
| `Select a run` | Switches run |
| `Export CSV` | Downloads decisions CSV for the run |

### PO table

Columns: PO, supplier, plant, route, value, requirement count, status, approved by, approved at.

- Clicking the PO ID expands its requirements.
- The expanded table shows requirement ID, item, plant, value, route, and decision status.
- POs only appear after their first requirement is approved.
- A PO is `APPROVED` only when every requirement on it is approved.
- Empty state: `No POs in this run.`

## 6. Sourcing

**Purpose:** Track and send RFQ, RFP, CAPEX sourcing, and new-item sourcing events.

### Header controls

| Control | What it does |
| --- | --- |
| `Select a run` | Switches run |
| `Route` filter | Exact route match |
| `Status` filter | Exact event status match |

### Sourcing table

Columns: event ID, item, plant, value, route, suggestions, status, capex requirement, actions.

### Actions

| Status | Button | What it does |
| --- | --- | --- |
| `PENDING` and linked decision | `Approve` | Calls `POST /api/decisions/{decisionId}/approve`. Moves the sourcing event to `APPROVED`. |
| `APPROVED` | `Send RFQ` / `Send RFP` / `Send ...` | Calls `POST /api/sourcing-events/{id}/send`. Moves the event to `SENT` and records the sent timestamp. |
| `SENT` | None | Displays `Sent {timestamp}`. |

The page refreshes after each action. Empty state: `No sourcing events match the filters.`

## 7. Manual PR

**Purpose:** Fallback intake when CSV upload or chat is unavailable. Creates one PR with one or more lines, validates it, optionally submits it to the engine, and shows the resulting decisions.

### Header control

`Submit to engine` checkbox:

- Checked: saving also submits the PR to the engine.
- Unchecked: saves a draft PR without processing it.

### PR form

| Field | Required | Meaning |
| --- | --- | --- |
| `Requester` | Yes | Requester name |
| `Department` | Yes | Department name |
| `PR number` | No | Generated by the backend if blank |
| `Item code` | Yes | Catalogue item code |
| `Description` | No | Item description |
| `Quantity` | Yes | Must be greater than zero |
| `UOM` | Yes | Unit of measure |
| `Plant` | Yes | `PUN1`, `CHK1`, etc. |
| `Estimated unit price` | No | Used for new-item valuation |
| `Need-by date` | Yes | Requested delivery date |
| `Urgency` | Yes | `Normal`, `Urgent`, or `Emergency` |

### Buttons

| Button | What it does |
| --- | --- |
| `Remove` | Removes a line. Disabled when only one line remains. |
| `Add line` | Adds another blank line with default need-by date `2026-11-20` and urgency `normal`. |
| `Create and submit PR` | Shown when `Submit to engine` is checked. Creates the PR and immediately submits it. |
| `Save draft PR` | Shown when `Submit to engine` is unchecked. Saves without submitting. |

### Validation

Each row is validated in the browser before any API call:

- Item code is required
- Quantity must be greater than zero
- UOM is required
- Plant is required
- Need-by date is required

Errors appear under the affected line. The backend also validates again.

### Submitted result

After submission the page shows `Submitted run #{runId}` with:

- PR line
- Item
- Match method
- Decision
- Route
- Validation status
- Reason

Problem rows are highlighted. If any row is `NOT_PROCESSED`, the page warns the user to correct and resubmit.

## 8. Buyer chat

**Purpose:** Conversational intake. It fills PR slots or drafts policy amendments. It never approves or activates policy.

### Chat controls

| Control | What it does |
| --- | --- |
| Message input | Accepts a PR request or policy change |
| `Send` | Sends the message to `POST /api/chat/messages` |
| `PR example` | Fills the input with `Need 20 hex bolt M10x40 for Pune by 25 Nov` |
| `Policy example` | Fills the input with `From 1 Dec, emergency limit is 3 lakh for Chakan` |
| `Reset` | Clears the conversation after the welcome message and starts a new session |

The current session number is shown in the panel header. The frontend sends `today: "2026-11-16"` and trusted requester/department defaults.

### Message metadata

Assistant messages may show:

- Stage
- Intent
- PR ID
- Amendment ID
- Missing slots
- Plain-language diff changes for policy requests

### Behavior

- PR requests: the assistant fills slots such as item, quantity, plant, and need-by date, then asks for missing information.
- Policy requests: the assistant creates a draft amendment and shows what would change. Procurement must still validate, preview, approve, and activate it in the Policy console.
- Unknown or vague items are handled by asking a targeted question rather than guessing.
- Reset does not delete server-side drafts or PRs; it only clears the chat UI and session.

## 9. Policy console

**Purpose:** Inspect immutable policy versions and manage policy amendments from draft through activation.

### Published versions panel

Lists policy versions with label, effective date, and status.

- Clicking a version loads its rules, parameters, and facts.
- The currently open version is highlighted.

### Policy panel

Shows:

- Rule key
- Priority
- Condition JSON
- Outcome/action JSON
- Collapsible `Parameters` table with key, scope, and value

The panel subtitle shows the number of rules, parameters, and facts.

### Amendments panel

Lists amendments with ID, source, effective date, and status.

- `Refresh` reloads the amendment list.
- Clicking an amendment opens it.
- Empty state: `No amendments yet.`

### New amendment form

When no amendment is selected, the panel is titled `New amendment`.

| Control | What it does |
| --- | --- |
| Pasted text box | Accepts policy change text |
| `Extract pasted text` | Calls `POST /api/policies/describe`. The AI service parses text into operations. |
| `Upload policy PDF` | Uploads a PDF to `POST /api/policies/upload`. Extraction is advisory only. |
| `Effective from` | Effective date for the new policy version |
| `Source note` | Human-readable source or document note |
| `Operations (JSON array)` | Exact operations to apply |
| `Create draft amendment` | Calls `POST /api/policy-amendments`. Creates a DRAFT. |

Default form values:

- Effective date: `2026-12-01`
- Source: `manual`
- Operations: `[{"type":"set_parameter","key":"spot_limit","value":100000}]`

If extraction finds no operations, the UI warns the user to edit the JSON manually. Extraction never creates or approves an amendment by itself.

### Selected amendment

Shows:

- Source
- Effective date
- Created by
- Applied version ID
- Operations JSON
- Plain-language diff and content hash when available
- Source text when available

### Amendment action buttons

| Status | Buttons | What they do |
| --- | --- | --- |
| `DRAFT` or `VALIDATED` | `Validate` | Calls `POST /api/policy-amendments/{id}/validate`. Checks rules, parameters, scopes, effective date, and outcomes. Sets status to `VALIDATED`. |
| `DRAFT` or `VALIDATED` | `Preview` | Calls `POST /api/policy-amendments/{id}/preview`. Builds the diff and content hash. Sets status to `VALIDATED`. |
| `DRAFT` or `VALIDATED` | `Approve & apply` | Calls `POST /api/policy-amendments/{id}/approve`. Creates an immutable version. If effective date is today or past, version becomes `ACTIVE` and amendment becomes `APPLIED`. If future, version becomes `SCHEDULED`. |
| `APPROVED` | `Activate` | Calls `POST /api/policy-amendments/{id}/activate`. Required only after a two-step approval. |
| `DRAFT`, `VALIDATED`, or `APPROVED` | `Reject` | Calls `POST /api/policy-amendments/{id}/reject`. Sets terminal `REJECTED`. |
| `APPLIED` or `REJECTED` | None | Terminal state; no further transitions. |

### Add parameter operation

Available on a selected amendment. It prompts for:

1. Parameter key
2. Value

It calls `POST /api/policy-amendments/{id}/parameters`, adds a `set_parameter` operation, and resets the amendment to `DRAFT`.

### Policy amendment rules

- Effective dates in the past or today activate immediately when approved.
- Future dates create a `SCHEDULED` version.
- Earlier requirement dates keep the older policy version.
- A new policy version does not alter stored historical decisions.
- The system rejects unknown facts, unknown parameters, invalid routes, duplicate priorities, missing effective dates, stale previews, and outcomes pointing to blocked suppliers.

## 10. Contracts

**Purpose:** View the contract register, add or edit contracts manually, and extract contracts from PDFs.

### Header actions

| Control | What it does |
| --- | --- |
| `Add contract` | Opens an empty manual contract form |
| `Upload contract PDF` | Uploads a PDF to `POST /api/contracts/upload` and shows the extracted contract for review |

### Contract table

Columns:

- Contract ID
- Supplier ID and name
- Term start and end
- Value cap
- Consumed
- Remaining
- Status
- Line count
- `Edit`

Consumption shown includes seeded baseline plus this environment's stored run consumption. The baseline itself is never rewritten.

### Manual contract form

Fields:

- Contract ID
- Supplier ID
- Supplier name
- Status (`Active` or `Inactive`)
- Start date
- End date
- Value cap
- Baseline consumed
- Rate lines: item code, description, UOM, rate

Buttons:

| Button | What it does |
| --- | --- |
| `Add line` | Adds a blank rate line |
| `Remove` | Removes a rate line; disabled when only one line remains |
| `Save contract` | Calls `POST /api/contracts`. Saving replaces the contract's rate lines. |
| `Close` | Closes the form without saving |

### Extracted contract panel

When a PDF is uploaded, the page shows:

- Contract ID
- Supplier
- Term
- Value cap
- Extracted rate lines
- Extraction warnings

Buttons:

| Button | What it does |
| --- | --- |
| `Save contract` | Saves the extracted contract with status `Active` |
| `Discard` | Discards the extraction without saving |

## 11. Preferred suppliers

**Purpose:** Rank active suppliers for an item as of a selected date, inspect the ranking components, and export the top three.

### Controls

| Control | What it does |
| --- | --- |
| `As of` | Ranking date |
| `Item code` | Item to rank; blank means all items |
| `Rank` | Loads `/api/preferred-suppliers?as_of_date=...&item_code=...` |
| `Export top three` | Downloads `/api/preferred-suppliers/export.csv?as_of_date=...&item_code=...` |

Defaults:

- Date: `2026-11-01`
- Item: `ITM-1036`

The page loads automatically on first open.

### Ranking table

Columns:

- Item
- Rank
- Supplier
- Score
- Recency
- PO count
- Contract flag
- On-time percentage
- Quality rating
- Reference price
- Reference source

Ranking factors:

- Recency bucket
- PO frequency
- Contract coverage
- On-time delivery
- Quality rating

Preferred lists match the three worked examples:

- `ITM-1048`: S029 rank 1, S030 rank 2
- `ITM-1036`: S023, S022, S024
- `ITM-1016`: S009, S010

## 12. Audit trail

**Purpose:** Show every recorded state change.

The page loads `GET /api/audit` automatically.

Columns:

- When
- Actor
- Action
- Target
- Before to after

Examples of recorded actions:

- Batch processed
- Policy approved
- Decision overridden
- PR created
- Sourcing sent
- Chat action

The audit table is read-only. Empty state: `No audit records yet.`

## 13. End-to-end demo flow

1. Open `Buyer workspace`.
2. Click `Process sample batch`.
3. Confirm 186 PR lines, 149 requirements, 82 proposed POs, and 2 not processed.
4. Download the decisions CSV.
5. Open `Requirements`.
6. Expand a requirement to show PR lines, contract headroom, preferred suppliers, and policy trace.
7. Approve a requirement with a PO route. Open `Purchase orders` and confirm the PO appears.
8. Approve an RFQ/RFP requirement. Open `Sourcing` and send it.
9. Open `Policy console`.
10. Create an amendment, for example `spot_limit = 100000` effective `2026-11-10`.
11. Click `Validate`, then `Preview`, then `Approve & apply`.
12. Return to `Buyer workspace`, process the same batch again, and download the amended CSV.
13. Open `Preferred suppliers`, choose the as-of date, and export the top three.
14. Open `Audit trail` and show the recorded approvals and overrides.

## 14. CSV formats

### Decisions export

File: `submission/decisions_v5.csv` and `submission/decisions_amended.csv`

Header:

```text
pr_no,pr_line,resolved_item_code,group_id,po_id,decision,route,supplier_id,unit_price,group_value_inr,suggested_supplier_ids,policy_version,reason
```

Rules:

- Exactly 13 columns.
- Blank values are empty strings, never the literal `null`.
- `NOT_PROCESSED` rows have an empty `decision`.
- Monetary values are numeric INR.
- `suggested_supplier_ids` uses semicolon-separated supplier IDs.
- Group-level values repeat across lines that belong to the same requirement.

### Preferred supplier export

File: `submission/preferred_suppliers_top3.csv`

Header:

```text
item_code,rank,supplier_id,reference_price
```

### PR upload format

The upload CSV must contain the expected PR columns:

```text
pr_no,pr_line,created_at,requester,department,plant,item_code,description,quantity,uom,estimated_unit_price,need_by_date,urgency
```

## 15. Backend endpoints used by the UI

| UI area | Endpoint |
| --- | --- |
| Process sample | `POST /api/process-batch/sample` |
| Upload PR CSV | `POST /api/process-batch` |
| Run history | `GET /api/runs` |
| Open run | `GET /api/runs/{id}` |
| Workspace | `GET /api/runs/{id}/workspace` |
| Decisions for override | `GET /api/decisions?run_id={id}` |
| Export decisions | `GET /api/runs/{id}/export.csv` |
| Approve decision | `POST /api/decisions/{id}/approve` |
| Override decision | `POST /api/decisions/{id}/override` |
| Send sourcing event | `POST /api/sourcing-events/{id}/send` |
| Create manual PR | `POST /api/prs` |
| Submit manual PR | `POST /api/prs/{id}/submit` |
| Chat | `POST /api/chat/messages` |
| Policy versions | `GET /api/policies` |
| Policy version detail | `GET /api/policies/{id}` |
| Describe/paste policy | `POST /api/policies/describe` |
| Upload policy PDF | `POST /api/policies/upload` |
| List amendments | `GET /api/policy-amendments` |
| Create amendment | `POST /api/policy-amendments` |
| Get amendment | `GET /api/policy-amendments/{id}` |
| Validate amendment | `POST /api/policy-amendments/{id}/validate` |
| Preview amendment | `POST /api/policy-amendments/{id}/preview` |
| Diff amendment | `GET /api/policy-amendments/{id}/diff` |
| Add parameter operation | `POST /api/policy-amendments/{id}/parameters` |
| Approve amendment | `POST /api/policy-amendments/{id}/approve` |
| Activate amendment | `POST /api/policy-amendments/{id}/activate` |
| Reject amendment | `POST /api/policy-amendments/{id}/reject` |
| Contracts | `GET /api/contracts`, `POST /api/contracts` |
| Extract contract PDF | `POST /api/contracts/upload` |
| Preferred suppliers | `GET /api/preferred-suppliers` |
| Export preferred suppliers | `GET /api/preferred-suppliers/export.csv` |
| Audit log | `GET /api/audit` |

## 16. Important behavior and limitations

- The UI has no authentication. `requester`, `department`, and `actor` are trusted fields for this exercise.
- The workspace starts empty after a clean database reset. It is not an error if no runs or POs are visible.
- The `Requirements` and `Purchase orders` pages default to the first run returned by the backend.
- Policy extraction is advisory. A human must validate, preview, and approve any amendment.
- Chat policy changes always require explicit confirmation and never auto-approve.
- PDF-only contracts are supported through upload and extraction, with manual entry as the fallback.
- The application does not depend on Python for batch processing, policy activation, or CSV export.
- The hosted link is live only while the Docker stack and tunnel are running. A persistent host is required for the final submission.
- Run keys make repeated processing of the same batch and active policy return the same run.
- Contract consumption is recomputed from the seeded baseline; repeated runs do not compound consumption.

## 17. Blind-test operating notes

For the official blind test:

1. Start from a clean database.
2. Confirm policy `v5.0` is `ACTIVE`.
3. Run 1: process the supplied PR batch under `v5.0`.
4. Export to `submission/decisions_v5.csv`.
5. Apply the supplied amendment through the app's Policy console.
6. Run 2: reprocess the same batch.
7. Export to `submission/decisions_amended.csv`.
8. Export preferred suppliers as of the supplied date to `submission/preferred_suppliers_top3.csv`.
9. Push the three CSVs before the deadline.
10. Do not change code after the build deadline.
