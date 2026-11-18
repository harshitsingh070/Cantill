# DECISIONS

This file records the judgement calls behind the Corvo PR-to-PO implementation, following the Section 17
checklist in `APPROACH.md`. Each entry states what the source material showed, what the implementation does,
and why. The engine is deterministic: the same batch, policy snapshot, and seed data always produce the same
decisions and byte-identical CSV output.

## Scope and stack

- The deterministic decision engine lives in Spring Boot because it owns the system of record, policy versions,
  audit trail, and exports. The Python FastAPI service only proposes extractions and chat slots; it never decides
  a route, supplier, price, or approval.
- Batch processing has no runtime dependency on Python. If the AI service is down, `/api/process-batch` and exports
  continue to work. Only PDF extraction and the optional AI-assist endpoints return `503`.
- The frontend is a React 18 + Vite single-page app (the brief allows React/Next). It is served by nginx in Docker.
- Java 17 is used because it is installed in the verification environment; the Spring Boot 3.3.5 code is Java 21
  compatible. H2 in PostgreSQL compatibility mode is the default local database; Docker Compose uses PostgreSQL 16.

## Matching, validation, and line handling

**1. Item matching.**
Noticed: PR descriptions are noisy and sometimes contain aliases. Decision: normalise text (lowercase, strip
punctuation/noise words), expand the `item_aliases` lexicon, score each catalogue item with
`0.50 × Jaccard token score + 0.50 × fuzzy sequence ratio`, and accept the best item only when the score is at
least `0.50`. Ties sort by item code. Below-threshold returns `NEW` with `match_method = below-threshold` and the
top five candidates for audit. Why: the weights and threshold are policy parameters (`item_match_*`), so they can
be tuned without code; the 0.50 baseline reproduces every worked example.

**2. Below-threshold Capex near-miss.**
Noticed: a description close to a Capex item can score just under the threshold. Decision: it is a new item, not
`CAPEX_SOURCING`. `NEW_ITEM_SOURCING` uses the new-item path and never joins a club. Why: rule 1.2 (new item) has
higher priority than 5.1 (Capex), and accepting a near-miss would silently buy a different asset.

**3. Invalid item code.**
Noticed: some lines carry unknown codes such as `ITM-9999`. Decision: if the code is not in the catalogue, ignore it
for resolution, fall back to the description, and record the raw code plus the resolution method in `pr_lines` and
the decision reason. Why: preserving the buyer's intent is safer than rejecting the line, and the audit shows the
discrepancy.

**4. Code vs description conflict.**
Noticed: a valid code can disagree with the description. Decision: the code wins; the description is used only when
the code is invalid/blank. Match score is 1.0 and method is `code`/`item_code`. Why: a catalogue code is an explicit
identifier; overriding it with fuzzy text would be surprising.

**5. Invalid plant.**
Noticed: plants are `PUN1` and `CHK1` only; `PUN2` appears in the sample as a deliberate error. Decision: any
unknown/inactive plant yields a line decision of `NOT_PROCESSED`, with an explanatory reason. Why: a PO cannot be
routed to a plant that does not exist, and silently defaulting would corrupt demand.

**6. Zero or negative quantity.**
Decision: `NOT_PROCESSED` with a validation reason. No requirement or PO is created. Why: nonsensical demand must
not consume contract ceilings or produce a purchase value.

**7. UOM mismatch.**
Decision: `NOT_PROCESSED` with a manual-review reason when the line UOM is incompatible with the item's base UOM
(e.g. `KG` vs `NOS`). There is no conversion table. Why: conversion factors are not supplied and guessing them
would create financially wrong POs.

**8. Missing need-by date.**
Noticed: need-by drives urgency and consolidation. Decision: a line without a need-by date is `NOT_PROCESSED`,
retained in the batch result and CSV with the reason `missing need-by date`; chat asks the buyer for the date before
building a decision. Why: the rule cannot calculate need-by offset or consistency without the date, and silently
treating it as normal would hide an incomplete requirement from the buyer. Dropping the row from the export would
make the omission invisible, so it remains visible as an exception.

**9. New item with no estimate.**
Decision: requirement value is `NULL`, route is `NEW_ITEM_SOURCING`, `group_value_inr` is blank in the export, and
the line is never clubbed. The reason states that valuation is unavailable. Why: the policy explicitly routes new
items to sourcing; inventing a price would create a false ceiling or approval test.

**10. New item always new-item sourcing.**
Decision: even a Capex-like new item follows `NEW_ITEM_SOURCING`, because `v5.0.1.2` outranks `v5.0.5.1`.
Why: the policy is explicit about new-item precedence, and this is a deliberate exception to value-based routing.

**11. New items are never clubbed.**
Decision: each new-item line is its own requirement/decision; the consolidation window does not merge new items.
Why: without a catalogue item or price, grouping would mix unrelated unknowns and make sourcing hard to evaluate.

**12. Duplicate PR lines.**
Decision: duplicate rows are processed separately as individual lines, but distinct PR lines for the same
item/plant within the club window still club into one requirement under 2.2. Why: the brief requires per-line
accountability, while consolidation is about demand, not duplicate text.

## Valuation and contracts

**13. 3.1(b) valuation is status-agnostic.**
Decision: when a contract cannot value a requirement, PO history supplies the unit price even if the historical
supplier is now Blocked/Inactive. Why: 3.1(b) is a price reference, not a sourcing eligibility test.

**14. 5.3/5.4 Active-supplier test is separate.**
Decision: Blocked/Inactive suppliers never count as active history for the emergency/spot rules. Why: policy
5.3/5.4 exists to constrain who may be used now, not merely who traded before.

**15. Blocked contract exclusion.**
Decision: a Blocked contract is excluded from applicability and from valuation fallback. Its lines and prices are
not usable for that requirement. Why: the supplier is not eligible, so neither its prices nor its ceiling may
drive the outcome.

**16. Expired contract still qualifies for 6.1(b).**
Decision: an expired contract still gives a supplier the contract bonus in the preferred list when 6.1(b) allows
it; it does not make the supplier usable for a contract route. Why: the ranking rule is about demonstrated
relationship/price history, while route eligibility is a separate contract-validity test.

**17. Lowest-rate usable contract.**
Decision: among applicable, Active contracts with remaining ceiling, choose the lowest unit rate for the item;
ties prefer the latest start date, then the highest contract ID. A contract with no ceiling is usable (unbounded).
An empty applicable set falls through to the next rule. Why: this is the stated 4.1 ordering and avoids picking an
arbitrary contract.

**18. Capex and contracts.**
Decision: no contract is "used" when 5.1 (Capex) wins; value comes from PO history or standard price, and no
contract consumption is recorded. Why: the contract route was not taken; charging its ceiling would falsify usage.

**19. Ceiling is run-scoped.**
Decision: within a run, the engine recomputes remaining ceiling from the immutable contract baseline plus earlier
requirements in the same run (ordered by requirement date, first line time, PR number, line). `contract_consumption_runs`
is audit-only and is never used as the next run's baseline. Why: repeat runs must be deterministic and blind-test
Run 1/Run 2 must produce identical values unless the policy/contract baseline changes.

**20. Pre-registered requirement dates and baseline accuracy.**
Noticed: contract baselines reflect the register as of 31 October 2026; a requirement dated earlier could see
consumption that happened later. Decision: use the baseline as supplied and document the caveat. The November
sample batch avoids this. Why: reconstructing historical consumption was not part of the provided data.

**21. Most recent PO convention.**
Decision: when multiple PO history rows share the same date, the highest `po_number` is treated as the most recent.
Why: the brief does not define intra-day ordering; this is deterministic and documented rather than guessed from
data that cannot be verified.

**22. ITM-1003 is contract precedence.**
Noticed: ITM-1003 could look like a tie-break example. Decision: it is a contract-precedence case; the contract
wins over PO history when applicable. Why: it reproduces the worked example without inventing a tie.

**23. Contract tie-break.**
Decision: latest start date, then highest contract ID. Why: this is the specified 4.x tie-break and is stable.

**24. Supplier status is current.**
Noticed: only a current status exists, with no status history. Decision: every evaluation uses the supplier's
current status, even for historical requirements. Why: no historical status data exists; pretending otherwise would
require invented data.

**25. Recency buckets.**
Decision: PO history within 90 days is the strongest bucket, within 365 days is the middle bucket, older is the
weakest (zero recency score). The 365-day window is the `active_history_days` parameter. Why: this matches the
policy's active-history notion and the worked rankings.

**26. Ranking weights and formula.**
Decision: `0.40 × recency + 0.25 × normalised 548-day PO count + 0.20 × contract bonus + 0.10 × on-time + 0.05 ×
quality`, ties by supplier ID ascending. All weights are policy parameters. Why: the baseline reproduces the three
preferred-supplier worked examples; parameterising them keeps the policy no-code.

**27. Category top-up order.**
Decision: order by `0.5 × on-time% + 0.5 × quality/5` descending, then supplier ID ascending. A strict on-time-first
rule contradicts ITM-1006 and ITM-1034, so the combined score is used. Why: the worked examples are the authority
when prose is ambiguous.

**28. Preferred list before top-up.**
Decision: the engine first materialises the preferred list under 6.1(b), then tops up from the category when fewer
than the requested number qualify. Why: this is the stated step order and avoids promoting a weaker supplier before
eligible preferred ones.

**29. Suggestion pool exhaustion.**
Decision: return fewer than the requested number rather than padding with ineligible suppliers. Why: a suggestion
is an actionable supplier; padding would violate the eligibility rules.

**30. Consolidation and PO IDs.**
Decision: consolidate requirements for the same supplier/plant/route when the difference between requirement
datetimes is at most `consolidation_hours` from the requirement's effective policy (baseline 24, inclusive). PO
candidate IDs are deterministic sequential `PO-0001`, `PO-0002`, … in processing order; the PDF IDs are
illustrative. The candidate becomes a persisted PO on first approval; if the candidate ID already exists from another
run, the stored PO gets a run suffix. Why: PR-26-00174/175 sort before the example 5 requirement, so a sequential
full-batch scheme shifts the illustrative IDs; the implementation stays internally consistent and documents this.

## Decisions, export, and edge cases

**31. Emergency is requirement-level.**
Decision: if any line in a clubbed requirement is Emergency, the requirement is Emergency. Why: urgency is a
property of the need, and the most urgent line must not be slowed by a calmer sibling.

**32. Precedence.**
Decision: 5.2 beats emergency; 5.1 beats contract. Emergency does not bypass Capex or the higher-value rules.
Why: this is the stated rule order and preserves financial controls.

**33. `NOT_PROCESSED` representation.**
Decision: `decision` is blank (`""`), `route` carries `NOT_PROCESSED`, and the reason explains the validation
failure. `group_id`, `po_id`, supplier/price, value, and suggestions are blank. Why: the brief lists only `PO` and
`SOURCING_REQUEST` as decision values and specifies the route for skipped lines; this interpretation keeps the
decision vocabulary clean while making skips filterable.

**34. CSV blanks.**
Decision: blank fields are empty strings, never the literal `null`. Why: the 13-column export must be directly
usable and comparable across runs.

**35. Rounding.**
Decision: calculate with exact unit prices and round monetary output to two decimals (half-up). Internal scores
use higher precision. Why: prevents cumulative rounding drift and matches the worked values.

**36. Manual amendment fallback.**
Decision: the manual editor and paste-text path use the same operation model, validation, diff, hashing, approval,
and activation APIs as extraction. Manual is not a reduced path. Why: the blind test must survive AI/PDF failure
within the time box.

**37. Chat relative dates.**
Decision: resolve "today/tomorrow/next Friday" against a supplied `today` (tests use `2026-11-16`; runtime uses the
Asia/Kolkata date). Ambiguous dates produce a clarification slot, not a guess. Why: deterministic tests and buyer
expectations both require an explicit clock.

**38. Chat noise and aliases.**
Decision: strip conversational noise ("please", "need", "for"), expand aliases, and match item codes first. If a
slot is still missing, ask a single targeted question rather than guessing. Why: a chat assistant must be
predictable and auditable, especially when it creates drafts.

**39. Multi-line PR.**
Decision: split chat input on semicolons and "and" when it describes multiple items, then fill slots per line.
Why: buyers naturally batch requests; one-line-at-a-time would be tedious.

**40. Service/monthly UOM.**
Decision: treat `MON`, `JOB`, `SET`, etc. as valid base UOMs; do not convert. Monthly facility services are priced
per month and routed by value like any other item. Why: no conversion table is supplied, and facility contracts
already state their billing unit.

**41. Emergency with high value and no usable contract/history.**
Decision: Emergency has no special effect when value exceeds the relevant boundaries and no usable contract or
Active history exists; 5.5/5.6 apply normally. Why: urgency changes the route only through the emergency rule's
conditions; it cannot manufacture eligibility.

**42. Preferred export.**
Decision: export only qualifying suppliers, top three per item, with no padding. Why: a three-row file with blank
suppliers would be misleading.

**43. Displayed vs calculated prices.**
Decision: use exact PO/contract prices for valuation, show rounded prices in the UI/CSV. Documented exceptions:
ITM-1038 (PO 7300244 at 1166.41), ITM-1035 (7300225 at 1391.82), ITM-1066 (7300445 at 167312.43). ITM-1044 uses
the S028 valuation price 121.19 even though the PO line shows S027 at 121.59, matching the worked value 11,56,170.
Why: the PDF displays rounded numbers; exact arithmetic is needed to reproduce values.

**44. Processing order and ledger.**
Decision: process requirements by `(requirement_date, first_line_created_at, pr_no, pr_line)` and update the
in-memory contract ledger after each consuming requirement. Why: deterministic ceiling outcomes and stable POs.

**45. Preferred ranking formula.**
Decision: as in item 26; tie-break supplier ID ascending. Why: reproduces all three worked preferred lists.

**46. Preferred list materialised before 5.3/5.4.**
Decision: compute the full list and export before evaluating rules that need suggestions; the same snapshot serves
both. Why: avoids recomputation differences and matches the required step order.

**47. Duplicate vs clubbing.**
Decision: no deduplication of identical rows; distinct lines for the same item/plant club under 2.2. PR-26-00174/175
form one requirement. Why: duplicate rows are data, while clubbing is a demand policy.

## Policy-as-data

**48. Version selection.**
Decision: select by requirement date; ties by `effective_from` descending, `approved_at` descending, then `id`
descending. No effective version yields `NOT_PROCESSED`. Why: a requirement must be judged under the policy that was
in force on its date, and tie-breaks must be stable.

**49. `SCHEDULED` versions.**
Decision: a future-dated approved version is `SCHEDULED`; it becomes selectable only when the requirement date
reaches `effective_from`. Why: approving a future change must not alter earlier decisions.

**50. No-code boundary.**
Decision: new combinations, scopes, priorities, thresholds, and outcomes built from registered facts need no code.
A genuinely new fact requires a resolver and a `policy_facts` row. Why: this is the deliberate boundary between
policy editing and engineering.

**51. Chat policy authority.**
Decision: chat creates a draft amendment only after explicit confirmation; it never approves or activates. Why:
separation of duties and the stated policy workflow.

**52. Parameterised weights.**
Decision: ranking, top-up, and item-match weights/threshold are policy parameters; v5.0 seeds the values that
reproduce the worked examples. Why: tuning should not require a deployment.

**53. Manual and extraction parity.**
Decision: both paths create the same operations and go through the same validate/preview/approve/activate service.
Why: the blind-test fallback must be trustworthy.

**54. Atomic invalid drafts.**
Decision: unknown fact, invalid route, duplicate priority, missing effective date, or a Blocked-supplier outcome
rejects the whole draft; no partial version is written. Why: a half-applied policy is worse than a failed edit.

**55. Amendment tests.**
Decision: the acceptance tests include the Python-down manual path and a same-effective-date tie-break, using the
same APIs the UI calls. Why: proves the fallback instead of assuming it.

**56. Engine invariants.**
Decision: contract validity, ceiling arithmetic, and Blocked/Inactive exclusion are engine invariants that policy
rows may reference but cannot override. Why: no policy edit should be able to buy from a blocked supplier or exceed
a ceiling.

**57. Limits via `param`.**
Decision: seeded conditions reference `emergency_limit`, `spot_limit`, `rfq_rfp_boundary`, and `active_history_days`
through `param` leaves; no duplicate numeric literals. `set_parameter` changes decisions without editing rules.
Why: this is the core no-code requirement.

**58. Parameter resolution.**
Decision: resolve with `RequirementFacts` using `item_code` > `category + plant` > `plant` > `category` > global;
never by version+key alone. Why: scoped limits are only meaningful with the requirement's facts.

**59. Scope normalisation.**
Decision: `scope_json` is metadata; materialised rules carry one normalised `condition_json` with the scope merged
as a leading `all`. `update_rule` replaces leading scope leaves rather than appending. Why: a single evaluation
path prevents scope/double-application bugs.

**60. Derived facts.**
Decision: seed `need_by_offset_days` as `need_by_date − requirement_date`; compute `has_active_history` from the
version's `active_history_days`. Why: derived facts stay consistent with parameter changes.

## Buyer approval semantics

Noticed: the brief describes approval as the action that creates the PO or sourcing event, while the export is
generated from the persisted batch result and must remain deterministic. Decision: a batch stores each decision as
`PROPOSED` with the candidate `po_id`, and creates sourcing events for sourcing routes, but does not create `pos` or
`po_lines` rows. The first approval of a PO decision creates the PO and all of its `po_lines`; later approvals update
the same PO. A PO becomes `APPROVED` only when all decisions on that PO are approved; otherwise it is
`PARTIALLY_APPROVED`. An override to a PO route creates the PO immediately because the override is itself the buyer's
approval action. An approved sourcing decision moves its event to `APPROVED`, and a separate send action records
`SENT` with the sender and timestamp. Why: this matches the stated state machine while keeping the golden
13-column export and repeat-run determinism unchanged.

Noticed: an override may change the supplier or route. Decision: overrides require a written comment, preserve the
original decision snapshot in `original_decision_json`, and write before/after audit rows. Why: the brief requires
the original decision and approver identity to remain inspectable.

## Known limitations and interpretations

- No authentication or role enforcement is implemented. The API trusts the `requester`/`department`/`actor` fields
  and the UI labels actions by role. The approve and activate endpoints accept an actor parameter and record it in the
  audit trail. A production deployment would add Spring Security with role-based access control.
- Policy v5.0 intentionally seeds only global parameters. Scoped parameters are introduced through policy amendments,
  exactly as the policy-as-data model requires; their absence from the seed is not a missing feature.
- The hosted Render deployment is live at `https://cantillcantill-web.onrender.com/`; the frontend proxies API calls to
  the Spring Boot backend and PostgreSQL. Free Render instances cold-start after inactivity, so the first request can
  be slow. AI-assisted policy/contract extraction depends on the Python service and its shared token; the manual policy
  editor remains the fallback if AI is unavailable.
- The Python service uses deterministic regex parsers, not an external LLM. It returns proposals, handles the
  provided PDFs, and can be swapped for an LLM behind the same endpoints. Policy PDF extraction can be messy; the
  API returns warnings and the UI routes the buyer to paste/manual fallback.
- `contract_consumption_runs` is intentionally audit-only; see item 19. Do not use it as the next baseline.
