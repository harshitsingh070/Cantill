# Blind-test runbook

The stack is already seeded with policy `v5.0` active and the contract register (including
the two PDF-only contracts). The folder `submission/` must end up with exactly three CSVs.

## 0. Start clean

```powershell
docker compose down -v
docker compose up -d
docker compose ps
```

Open http://localhost:5173. Confirm the workspace is empty and the Policy console shows one
`v5.0` version with status `ACTIVE`.

## 1. Upload and process the new PR batch (Run 1)

1. Buyer workspace -> choose the new PR batch CSV -> **Process batch**.
2. The run appears in run history. Note its run id.
3. Export decisions CSV. Save as `submission/decisions_run1.csv`.

API equivalent:

```powershell
curl.exe -X POST http://localhost:8080/api/process-batch `
  -F "file=@<new_batch>.csv" -F "uploadedBy=buyer"
curl.exe -o submission/decisions_run1.csv `
  http://localhost:8080/api/runs/<runId>/export.csv
```

## 2. Enter the policy amendment

Try the PDF path first, time-boxed to about 10 minutes:

1. Policy console -> **Policy intake** -> upload the amendment PDF.
2. Review the extracted operations, effective date, and plain-language preview.
3. Fix anything missing with the manual JSON editor (no AI/Python dependency).

If extraction is slow or wrong, paste the amendment text into the paste box. If that also
fails, add the operations manually. Formats are in `docs/API.md` under Policy amendments, for
example:

```json
[
  {"type":"set_parameter","key":"spot_limit","value":100000,"scope_json":{"category":"Safety & PPE"}}
]
```

Then **Validate** -> **Preview diff** -> confirm the effective date -> **Approve and activate**.
The new version should appear as `ACTIVE` (or `SCHEDULED` if the effective date is in the
future). Chat may draft an amendment but never approves or activates it.

API equivalent:

```powershell
curl.exe -X POST http://localhost:8080/api/policy-amendments `
  -H "Content-Type: application/json" `
  -d "{\"effective_from\":\"<yyyy-mm-dd>\",\"source\":\"manual\",\"created_by\":\"procurement\",\"operations\":[...]}"
curl.exe -X POST http://localhost:8080/api/policy-amendments/1/validate
curl.exe -X POST http://localhost:8080/api/policy-amendments/1/preview
curl.exe -X POST http://localhost:8080/api/policy-amendments/1/approve `
  -H "Content-Type: application/json" -d "{\"actor\":\"procurement\"}"
```

If two-step mode is requested, pass `{"two_step":true}` and call
`POST /api/policy-amendments/{id}/activate` afterwards.

## 3. Reprocess the same batch (Run 2)

1. Buyer workspace -> upload the identical CSV again -> **Process batch**.
2. A new run id is created because the active policy changed.
3. Export decisions CSV. Save as `submission/decisions_run2.csv`.

API equivalent: repeat the upload command, then export the new run id.

## 4. Export preferred suppliers

Use the as-of date supplied by the test.

1. Preferred suppliers tab -> set the date -> **Export top 3**.
2. Save as `submission/preferred_suppliers_top3.csv`.

API equivalent:

```powershell
curl.exe -o submission/preferred_suppliers_top3.csv `
  "http://localhost:8080/api/preferred-suppliers/export.csv?as_of_date=<yyyy-mm-dd>"
```

## 5. Final checks

- `submission/` contains exactly the three CSVs above.
- Each decisions CSV has the exact 13-column header and 186 data rows for the sample batch
  (the new batch may differ; use its actual row count).
- Every row has 13 fields, blank fields are empty strings, and `NOT_PROCESSED` rows have an
  empty `decision`.
- Repeat uploads of the same file and policy return the same run id and byte-identical CSV.
- `docker compose stop ai` then reprocessing the batch gives byte-identical output; the batch,
  policy activation, and exports do not depend on Python.
- Audit trail shows the policy approvals and any buyer overrides with before/after values.

Do not change code during the test. If something fails, use the manual amendment editor and
the API fallbacks above rather than editing source.
