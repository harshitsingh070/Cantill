import { Fragment, useEffect, useMemo, useState } from 'react'
import { api, money } from './api'

const TABS = [
  ['workspace', 'Buyer workspace'],
  ['requirements', 'Requirements'],
  ['pos', 'Purchase orders'],
  ['sourcing', 'Sourcing'],
  ['prs', 'Manual PR'],
  ['chat', 'Buyer chat'],
  ['policy', 'Policy console'],
  ['contracts', 'Contracts'],
  ['preferred', 'Preferred suppliers'],
  ['audit', 'Audit trail']
]

function pick(object, ...keys) {
  if (!object) return undefined
  for (const key of keys) {
    if (object[key] !== undefined && object[key] !== null) return object[key]
  }
  return undefined
}

function Badge({ value }) {
  const text = String(value ?? '')
  let kind = 'neutral'
  if (['READY_FOR_PO', 'PROCEED', 'ACTIVE', 'APPLIED', 'VALIDATED', 'Active', 'CONTRACT', 'APPROVED', 'SENT'].includes(text)) kind = 'good'
  if (['BLOCKED', 'NOT_PROCESSED', 'REJECTED', 'INACTIVE', 'Invalid'].includes(text)) kind = 'bad'
  if (['PENDING', 'DRAFT', 'SCHEDULED', 'SPOT', 'RFQ', 'PREFERRED', 'NEW_ITEM', 'PROPOSED', 'RFP'].includes(text)) kind = 'warn'
  return <span className={`badge ${kind}`}>{text || '—'}</span>
}

function ErrorBox({ error }) {
  return error ? <div className="error">{error}</div> : null
}

function Notice({ message }) {
  return message ? <div className="notice">{message}</div> : null
}

function Panel({ title, subtitle, children, actions }) {
  return (
    <section className="panel">
      <header className="panel-head">
        <div>
          <h2>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {actions && <div className="actions">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

function Stat({ label, value, tone }) {
  return (
    <div className={`stat ${tone || ''}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function App() {
  const [tab, setTab] = useState('workspace')

  useEffect(() => {
    const timer = window.setInterval(() => {
      fetch('/api/health', { cache: 'no-store' }).catch(() => {})
    }, 10 * 60 * 1000)
    return () => window.clearInterval(timer)
  }, [])

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">C</span>
          <div>
            <strong>Corvo</strong>
            <small>Procurement intelligence</small>
          </div>
        </div>
        <nav>
          {TABS.map(([id, label]) => (
            <button key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>
              {label}
            </button>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className="dot" /> PR to PO · deterministic engine
        </div>
      </aside>
      <main className="main">
        {tab === 'workspace' && <Workspace />}
        {tab === 'requirements' && <RequirementsBoard />}
        {tab === 'pos' && <PurchaseOrders />}
        {tab === 'sourcing' && <SourcingBoard />}
        {tab === 'prs' && <ManualPr />}
        {tab === 'chat' && <BuyerChat />}
        {tab === 'policy' && <PolicyConsole />}
        {tab === 'contracts' && <Contracts />}
        {tab === 'preferred' && <PreferredSuppliers />}
        {tab === 'audit' && <AuditTrail />}
      </main>
    </div>
  )
}

function Workspace() {
  const [runs, setRuns] = useState([])
  const [result, setResult] = useState(null)
  const [activeRun, setActiveRun] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('')
  const [decisions, setDecisions] = useState([])
  const [message, setMessage] = useState('')

  async function loadRuns() {
    try {
      setRuns(await api('/runs'))
    } catch (cause) {
      setError(cause.message)
    }
  }

  useEffect(() => {
    loadRuns()
  }, [])

  async function openRun(id) {
    setBusy(true)
    setError('')
    setMessage('')
    try {
      setResult(await api(`/runs/${id}`))
      setDecisions(await api(`/decisions?run_id=${id}`))
      setActiveRun(id)
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  async function processSample() {
    setBusy(true)
    setError('')
    try {
      const response = await api('/process-batch/sample', { method: 'POST' })
      setResult(response.result)
      setActiveRun(response.runId)
      setDecisions(await api(`/decisions?run_id=${response.runId}`))
      await loadRuns()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  async function upload(event) {
    const file = event.target.files?.[0]
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    setBusy(true)
    setError('')
    try {
      const response = await api('/process-batch', { method: 'POST', body: form })
      setResult(response.result)
      setActiveRun(response.runId)
      setDecisions(await api(`/decisions?run_id=${response.runId}`))
      await loadRuns()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  const lines = result?.lines || []
  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (!needle) return lines
    return lines.filter((line) =>
      [line.prNo, line.resolvedItemCode, line.decision, line.route, line.supplierId, line.poId, line.reason]
        .some((value) => String(value ?? '').toLowerCase().includes(needle))
    )
  }, [lines, filter])

  const notProcessed = lines.filter((line) => line.route === 'NOT_PROCESSED' || line.decision === 'NOT_PROCESSED').length
  const poCount = new Set(lines.map((line) => line.poId).filter(Boolean)).size
  const requirementCount = result?.requirements?.length ?? 0

  const decisionIndex = useMemo(() => {
    const byRequirement = new Map(decisions.map((decision) => [decision.requirementId, decision]))
    const byLine = new Map()
    for (const requirement of result?.requirements || []) {
      const decision = byRequirement.get(requirement.id)
      if (!decision) continue
      for (const line of requirement.lines || []) byLine.set(line.id, decision)
    }
    return byLine
  }, [result, decisions])

  async function override(line) {
    const decision = decisionIndex.get(line.prLineId)
    if (!decision) {
      setError('This line has no stored decision to override.')
      return
    }
    const route = (prompt('Override route', decision.route || 'SPOT_PO') || '').trim().toUpperCase()
    if (!route) return
    const poRoute = ['CONTRACT_PO', 'EMERGENCY_PO', 'SPOT_PO'].includes(route)
    const supplierId = poRoute
      ? prompt('Override to which Active supplier ID?', decision.supplierId || 'S023')
      : null
    if (poRoute && !supplierId) return
    const unitPrice = poRoute
      ? prompt('Override unit price', decision.unitPrice ?? '')
      : null
    if (poRoute && (unitPrice === null || unitPrice === '')) return
    const comment = prompt('Override comment (required for the audit trail)')
    if (!comment) return
    try {
      const body = { route, comment, actor: 'buyer' }
      if (poRoute) {
        body.supplier_id = supplierId
        body.unit_price = Number(unitPrice)
      }
      const updated = await api(`/decisions/${decision.id}/override`, {
        method: 'POST',
        body: JSON.stringify(body)
      })
      setDecisions((current) => current.map((item) => (item.id === updated.id ? updated : item)))
      setMessage(`Decision #${updated.id} overridden to ${updated.route}.`)
    } catch (cause) {
      setError(cause.message)
    }
  }

  return (
    <div className="stack">
      <Panel
        title="Buyer workspace"
        subtitle="Process a PR batch, inspect every deterministic decision, and export the result."
        actions={
          <>
            <button className="secondary" onClick={processSample} disabled={busy}>
              {busy ? 'Working…' : 'Process sample batch'}
            </button>
            <label className={`button ${busy ? 'disabled' : ''}`}>
              Upload PR CSV
              <input type="file" accept=".csv,text/csv" onChange={upload} disabled={busy} hidden />
            </label>
          </>
        }
      >
        <ErrorBox error={error} />
        <Notice message={message} />
        <div className="stats">
          <Stat label="Run" value={activeRun ? `#${activeRun}` : '—'} />
          <Stat label="PR lines" value={lines.length} />
          <Stat label="Requirements" value={requirementCount} />
          <Stat label="POs proposed" value={poCount} />
          <Stat label="Not processed" value={notProcessed} tone={notProcessed ? 'bad' : ''} />
        </div>
        {result && (
          <div className="link-row">
            <a className="link" href={`/api/runs/${activeRun}/export.csv`}>Download decisions CSV</a>
            <span className="muted">Policy used: {result.lines?.[0]?.policyVersion || '—'}</span>
          </div>
        )}
      </Panel>

      <div className="split">
        <Panel title="Runs" subtitle="Audit-friendly batch history.">
          <div className="list">
            {runs.map((run) => (
              <button
                key={run.id}
                className={`list-item ${activeRun === run.id ? 'selected' : ''}`}
                onClick={() => openRun(run.id)}
              >
                <div>
                  <strong>Run #{run.id}</strong>
                  <small>{run.filename} · {run.policy_version_label}</small>
                </div>
                <span className="muted">{run.created_at ? String(run.created_at).slice(0, 16).replace('T', ' ') : ''}</span>
              </button>
            ))}
            {!runs.length && <p className="muted">No runs yet. Process the sample batch.</p>}
          </div>
        </Panel>

        <Panel
          title="Decisions"
          subtitle={result ? `${visible.length} of ${lines.length} lines` : 'Open or process a run.'}
          actions={
            result ? (
              <input
                className="search"
                placeholder="Filter PR, item, supplier, PO…"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
            ) : null
          }
        >
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>PR</th>
                  <th>Item</th>
                  <th>Decision</th>
                  <th>Route</th>
                  <th>Clause</th>
                  <th>Supplier</th>
                  <th className="right">Unit price</th>
                  <th className="right">Value</th>
                  <th>PO</th>
                  <th>Reason</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {visible.map((line) => {
                  const decision = decisionIndex.get(line.prLineId)
                  return (
                    <tr key={line.prLineId} className={line.route === 'NOT_PROCESSED' ? 'row-bad' : ''}>
                      <td className="nowrap">{line.prNo}-{line.prLine}</td>
                      <td>{line.resolvedItemCode || '—'}</td>
                      <td><Badge value={line.decision} /></td>
                      <td>{line.route || '—'}</td>
                      <td className="nowrap">{decision?.clause || '—'}</td>
                      <td className="nowrap">
                        {decision?.supplierId || line.supplierId || '—'}
                        {decision?.overriddenAt && <span className="override-dot" title={`Overridden: ${decision.overrideComment || ''}`} />}
                      </td>
                      <td className="right">{money(line.unitPrice)}</td>
                      <td className="right">{money(line.groupValueInr)}</td>
                      <td className="nowrap">{line.poId || '—'}</td>
                      <td className="reason">{line.reason}</td>
                      <td>
                        {decision && (
                          <button className="ghost tiny" onClick={() => override(line)} disabled={busy}>
                            Override
                          </button>
                        )}
                      </td>
                    </tr>
                  )
                })}
                {result && !visible.length && (
                  <tr><td colSpan="11" className="muted">No lines match the filter.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </div>
  )
}

function RunSelector({ runs, runId, onChange }) {
  return (
    <select value={runId || ''} onChange={(event) => onChange(Number(event.target.value))}>
      <option value="" disabled>Select a run</option>
      {runs.map((run) => (
        <option key={run.id} value={run.id}>
          #{run.id} · {run.status} · {run.createdAt || run.created_at || ''}
        </option>
      ))}
    </select>
  )
}

function useWorkspaceData() {
  const [runs, setRuns] = useState([])
  const [runId, setRunId] = useState(null)
  const [workspace, setWorkspace] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api('/runs')
      .then((data) => {
        setRuns(data)
        if (data.length) setRunId(data[0].id)
      })
      .catch((cause) => setError(cause.message))
  }, [])

  useEffect(() => {
    if (!runId) return
    setBusy(true)
    setError('')
    api(`/runs/${runId}/workspace`)
      .then(setWorkspace)
      .catch((cause) => setError(cause.message))
      .finally(() => setBusy(false))
  }, [runId])

  return { runs, runId, setRunId, workspace, setWorkspace, error, setError, busy, setBusy }
}

function RequirementsBoard() {
  const { runs, runId, setRunId, workspace, setWorkspace, error, setError, busy, setBusy } = useWorkspaceData()
  const [filter, setFilter] = useState({ plant: '', route: '', status: '', policy: '', query: '' })
  const [expanded, setExpanded] = useState(null)
  const [message, setMessage] = useState('')

  const requirements = workspace?.requirements || []
  const routeOptions = [...new Set(requirements.map((item) => item.route).filter(Boolean))].sort()
  const statusOptions = [...new Set(requirements.map((item) => item.status || 'NOT_PROCESSED'))].sort()

  const visible = useMemo(() => {
    const needle = filter.query.trim().toLowerCase()
    return requirements.filter((item) => {
      if (filter.plant && item.plant !== filter.plant) return false
      if (filter.route && item.route !== filter.route) return false
      if (filter.status && (item.status || 'NOT_PROCESSED') !== filter.status) return false
      if (filter.policy && item.policyVersion !== filter.policy) return false
      if (!needle) return true
      return [item.itemCode, item.plant, item.route, item.supplierId, item.decisionId, item.reason]
        .some((value) => String(value ?? '').toLowerCase().includes(needle))
    })
  }, [requirements, filter])

  async function refresh() {
    if (!runId) return
    setWorkspace(await api(`/runs/${runId}/workspace`))
  }

  async function approve(item) {
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const result = await api(`/decisions/${item.decisionId}/approve`, {
        method: 'POST',
        body: JSON.stringify({ actor: 'procurement' })
      })
      setMessage(result.message || `Decision for ${item.itemCode} approved.`)
      await refresh()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  async function override(item) {
    const route = (prompt('Route', item.route || 'SPOT_PO') || '').trim().toUpperCase()
    if (!route) return
    const poRoute = ['CONTRACT_PO', 'EMERGENCY_PO', 'SPOT_PO'].includes(route)
    const supplier = poRoute ? prompt('Supplier ID', item.supplierId || '') : null
    if (poRoute && !supplier) return
    const unitPrice = poRoute ? prompt('Unit price', item.unitPrice ?? '') : null
    if (poRoute && (unitPrice === null || unitPrice === '')) return
    const comment = prompt('Override comment', 'Buyer override')
    if (comment === null) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const body = { route, comment, actor: 'procurement' }
      if (poRoute) {
        body.supplier_id = supplier
        body.unit_price = Number(unitPrice)
      }
      await api(`/decisions/${item.decisionId}/override`, {
        method: 'POST',
        body: JSON.stringify(body)
      })
      setMessage(`Decision for ${item.itemCode} overridden to ${route}.`)
      await refresh()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Notice message={message} />
      <Panel
        title="Requirement decisions"
        subtitle="One row per requirement. Expand for PR lines, contract headroom, preferred suppliers, and the policy trace."
        actions={
          <>
            <RunSelector runs={runs} runId={runId} onChange={setRunId} />
            {runId && <a className="button ghost" href={`/api/runs/${runId}/export.csv`}>Export CSV</a>}
          </>
        }
      >
        <div className="filters">
          <label>Plant
            <select value={filter.plant} onChange={(event) => setFilter({ ...filter, plant: event.target.value })}>
              <option value="">All</option>
              {[...new Set(requirements.map((item) => item.plant).filter(Boolean))].sort().map((plant) => (
                <option key={plant} value={plant}>{plant}</option>
              ))}
            </select>
          </label>
          <label>Route
            <select value={filter.route} onChange={(event) => setFilter({ ...filter, route: event.target.value })}>
              <option value="">All</option>
              {routeOptions.map((route) => <option key={route} value={route}>{route}</option>)}
            </select>
          </label>
          <label>Status
            <select value={filter.status} onChange={(event) => setFilter({ ...filter, status: event.target.value })}>
              <option value="">All</option>
              {statusOptions.map((status) => <option key={status} value={status}>{status}</option>)}
            </select>
          </label>
          <label>Policy
            <select value={filter.policy} onChange={(event) => setFilter({ ...filter, policy: event.target.value })}>
              <option value="">All</option>
              {[...new Set(requirements.map((item) => item.policyVersion).filter(Boolean))].sort().map((policy) => (
                <option key={policy} value={policy}>{policy}</option>
              ))}
            </select>
          </label>
          <label>Search
            <input value={filter.query} onChange={(event) => setFilter({ ...filter, query: event.target.value })} placeholder="Item, supplier, reason" />
          </label>
          <button className="ghost" onClick={() => setFilter({ plant: '', route: '', status: '', policy: '', query: '' })}>Clear</button>
        </div>
        <div className="table-wrap tall">
          <table>
            <thead>
              <tr>
                <th>Requirement</th><th>Plant</th><th className="right">Value</th><th>Route</th>
                <th>Supplier</th><th>Suggestions</th><th>Contracts</th><th>Policy</th>
                <th>Status</th><th className="right">Age</th><th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((item) => {
                const status = item.status || 'NOT_PROCESSED'
                return (
                  <Fragment key={item.id}>
                    <tr className={status === 'NOT_PROCESSED' ? 'row-bad' : ''}>
                      <td className="nowrap">
                        <button className="link-button" onClick={() => setExpanded(expanded === item.id ? null : item.id)}>
                          {item.itemCode}
                        </button>
                        <div className="muted small">REQ-{item.id} · {item.quantity} units</div>
                      </td>
                      <td>{item.plant}</td>
                      <td className="right">{money(item.value)}</td>
                      <td><Badge value={item.route || 'NOT_PROCESSED'} /></td>
                      <td>{item.supplierId || '—'}</td>
                      <td className="small">{item.suggestions?.length ? item.suggestions.slice(0, 3).join(' · ') : '—'}</td>
                      <td className="right">{item.applicableContracts?.length || 0}</td>
                      <td className="nowrap">{item.policyVersion || '—'}</td>
                      <td><Badge value={status} /></td>
                      <td className="right">{item.ageDays ?? '—'}</td>
                      <td className="nowrap">
                        {item.decisionId && status === 'PROPOSED' && (
                          <button className="ghost tiny" onClick={() => approve(item)} disabled={busy}>Approve</button>
                        )}
                        {item.decisionId && <button className="ghost tiny" onClick={() => override(item)} disabled={busy}>Override</button>}
                      </td>
                    </tr>
                    {expanded === item.id && (
                      <tr>
                        <td colSpan="11">
                          <div className="detail-grid">
                            <div>
                              <h3>PR lines</h3>
                              <div className="table-wrap">
                                <table>
                                  <thead><tr><th>PR line</th><th>Plant</th><th className="right">Qty</th><th>UOM</th><th>Match</th><th>Status</th></tr></thead>
                                  <tbody>
                                    {(item.prLines || []).map((line) => (
                                      <tr key={line.prLineId}>
                                        <td className="nowrap">{line.prNo}-{line.prLine}</td>
                                        <td>{line.plant}</td>
                                        <td className="right">{line.quantity}</td>
                                        <td>{line.uom}</td>
                                        <td className="small">{line.matchMethod || '—'} {line.matchScore != null ? `· ${line.matchScore}` : ''}</td>
                                        <td><Badge value={line.validationStatus} /></td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            </div>
                            <div>
                              <h3>Decision</h3>
                              <div className="meta-grid">
                                <div><span>Clause</span><strong>{item.clause || '—'}</strong></div>
                                <div><span>Group value</span><strong>{money(item.groupValueInr)}</strong></div>
                                <div><span>Reference</span><strong>{item.sourceReference || '—'}</strong></div>
                                {item.requiresCapexApproval && <div><span>Capex</span><strong>Approval required</strong></div>}
                              </div>
                              <p className="muted">Group value = {item.quantity} x {money(item.unitPrice)} = {money(item.value)}</p>
                              <p className="muted">{item.reason}</p>
                              {item.approvedBy && <p className="notice">Approved by {item.approvedBy} at {item.approvedAt}</p>}
                            </div>
                          </div>
                          <h3>Applicable contracts</h3>
                          {item.applicableContracts?.length ? (
                            <div className="table-wrap">
                              <table>
                                <thead><tr><th>Contract</th><th>Supplier</th><th className="right">Rate</th><th className="right">Cap</th><th className="right">Consumed before</th><th className="right">Consumed after</th><th className="right">Remaining after</th><th>Usable</th></tr></thead>
                                <tbody>
                                  {item.applicableContracts.map((contract) => (
                                    <tr key={contract.contractId}>
                                      <td className="nowrap">{contract.contractId}</td>
                                      <td>{contract.supplierId}</td>
                                      <td className="right">{money(contract.rate)}</td>
                                      <td className="right">{money(contract.valueCap)}</td>
                                      <td className="right">{money(contract.consumedBefore)}</td>
                                      <td className="right">{money(contract.consumedAfter)}</td>
                                      <td className="right">{money(contract.remainingAfter)}</td>
                                      <td>{contract.usable ? 'Yes' : 'No'}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          ) : <p className="muted">No applicable contract for this requirement.</p>}
                          <h3>Preferred suppliers</h3>
                          {item.preferredSuppliers?.length ? (
                            <div className="table-wrap">
                              <table>
                                <thead><tr><th className="right">Rank</th><th>Supplier</th><th className="right">Score</th><th className="right">Reference</th><th>Source</th><th>Contract</th><th className="right">POs</th><th>Recency</th><th>Components</th></tr></thead>
                                <tbody>
                                  {item.preferredSuppliers.map((supplier) => (
                                    <tr key={supplier.supplierId}>
                                      <td className="right">{supplier.rank}</td>
                                      <td>{supplier.supplierId}</td>
                                      <td className="right">{supplier.score}</td>
                                      <td className="right">{money(supplier.referencePrice)}</td>
                                      <td>{supplier.referenceSource}</td>
                                      <td>{supplier.hasContract ? 'Yes' : 'No'}</td>
                                      <td className="right">{supplier.poCount}</td>
                                      <td>{supplier.recencyBucket}</td>
                                      <td className="mono small">{JSON.stringify(supplier.scoreComponents)}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          ) : <p className="muted">No preferred supplier ranking for this route.</p>}
                          <details className="details">
                            <summary>Policy trace</summary>
                            <pre className="code">{JSON.stringify(item.trace, null, 2)}</pre>
                          </details>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
              {!visible.length && (
                <tr><td colSpan="11" className="muted">{busy ? 'Loading…' : 'No requirements match the filters.'}</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <h3>Not processed</h3>
        {workspace?.issues?.length ? (
          <div className="table-wrap">
            <table>
              <thead><tr><th>PR line</th><th>Item</th><th>Validation</th><th>Reason</th></tr></thead>
              <tbody>
                {workspace.issues.map((issue) => (
                  <tr key={`${issue.prNo}-${issue.prLine}`}>
                    <td className="nowrap">{issue.prNo}-{issue.prLine}</td>
                    <td>{issue.itemCode || '—'}</td>
                    <td><Badge value={issue.validationStatus} /></td>
                    <td className="reason">{issue.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="muted">None in this run.</p>}
      </Panel>
    </div>
  )
}

function PurchaseOrders() {
  const { runs, runId, setRunId, workspace, error, busy } = useWorkspaceData()
  const [expanded, setExpanded] = useState(null)
  const pos = workspace?.pos || []

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Panel
        title="Purchase orders"
        subtitle="Proposed POs appear here once their first requirement is approved. A PO becomes approved when every requirement on it is approved."
        actions={
          <>
            <RunSelector runs={runs} runId={runId} onChange={setRunId} />
            {runId && <a className="button ghost" href={`/api/runs/${runId}/export.csv`}>Export CSV</a>}
          </>
        }
      >
        <div className="table-wrap tall">
          <table>
            <thead>
              <tr><th>PO</th><th>Supplier</th><th>Plant</th><th>Route</th><th className="right">Value</th><th className="right">Requirements</th><th>Status</th><th>Approved by</th><th>Approved at</th></tr>
            </thead>
            <tbody>
              {pos.map((po) => (
                <Fragment key={po.poId}>
                  <tr>
                    <td className="nowrap">
                      <button className="link-button" onClick={() => setExpanded(expanded === po.poId ? null : po.poId)}>{po.poId}</button>
                    </td>
                    <td>{po.supplierId}</td>
                    <td>{po.plant}</td>
                    <td><Badge value={po.route} /></td>
                    <td className="right">{money(po.value)}</td>
                    <td className="right">{po.requirementCount}</td>
                    <td><Badge value={po.status} /></td>
                    <td>{po.approvedBy || '—'}</td>
                    <td className="nowrap">{po.approvedAt || '—'}</td>
                  </tr>
                  {expanded === po.poId && (
                    <tr>
                      <td colSpan="9">
                        <div className="table-wrap">
                          <table>
                            <thead><tr><th>Requirement</th><th>Item</th><th>Plant</th><th className="right">Value</th><th>Route</th><th>Decision status</th></tr></thead>
                            <tbody>
                              {(po.requirements || []).map((requirement) => (
                                <tr key={requirement.id}>
                                  <td>REQ-{requirement.id}</td>
                                  <td>{requirement.itemCode}</td>
                                  <td>{requirement.plant}</td>
                                  <td className="right">{money(requirement.value)}</td>
                                  <td>{requirement.route}</td>
                                  <td><Badge value={requirement.status} /></td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
              {!pos.length && <tr><td colSpan="9" className="muted">{busy ? 'Loading…' : 'No POs in this run.'}</td></tr>}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  )
}

function SourcingBoard() {
  const { runs, runId, setRunId, workspace, setWorkspace, error, setError, busy, setBusy } = useWorkspaceData()
  const [message, setMessage] = useState('')
  const [route, setRoute] = useState('')
  const [status, setStatus] = useState('')
  const rows = (workspace?.sourcing || []).filter((row) =>
    (!route || row.route === route) && (!status || row.status === status)
  )

  async function refresh() {
    if (!runId) return
    setWorkspace(await api(`/runs/${runId}/workspace`))
  }

  async function act(row, action) {
    setBusy(true)
    setError('')
    setMessage('')
    try {
      if (action === 'approve') {
        await api(`/decisions/${row.decisionId}/approve`, {
          method: 'POST',
          body: JSON.stringify({ actor: 'procurement' })
        })
        setMessage(`Sourcing for ${row.itemCode} approved. Send the ${row.route} when ready.`)
      } else {
        const result = await api(`/sourcing-events/${row.id}/send`, {
          method: 'POST',
          body: JSON.stringify({ actor: 'procurement' })
        })
        setMessage(result.message || `${row.route} sent for ${row.itemCode}.`)
      }
      await refresh()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Notice message={message} />
      <Panel
        title="Sourcing events"
        subtitle="RFQ/RFP events start pending. Approve the linked decision, then record that the request was sent."
        actions={<RunSelector runs={runs} runId={runId} onChange={setRunId} />}
      >
        <div className="filters">
          <label>Route
            <select value={route} onChange={(event) => setRoute(event.target.value)}>
              <option value="">All</option>
              {[...new Set((workspace?.sourcing || []).map((row) => row.route).filter(Boolean))].sort().map((value) => (
                <option key={value} value={value}>{value}</option>
              ))}
            </select>
          </label>
          <label>Status
            <select value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="">All</option>
              {[...new Set((workspace?.sourcing || []).map((row) => row.status).filter(Boolean))].sort().map((value) => (
                <option key={value} value={value}>{value}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="table-wrap tall">
          <table>
            <thead>
              <tr><th>Event</th><th>Item</th><th>Plant</th><th className="right">Value</th><th>Route</th><th>Suggestions</th><th>Status</th><th>Capex</th><th>Actions</th></tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td className="nowrap">SRC-{row.id}</td>
                  <td>{row.itemCode || '—'}</td>
                  <td>{row.plant || '—'}</td>
                  <td className="right">{money(row.value)}</td>
                  <td><Badge value={row.route} /></td>
                  <td className="small">{row.suggestions?.length ? row.suggestions.join(' · ') : '—'}</td>
                  <td><Badge value={row.status} /></td>
                  <td>{row.requiresCapexApproval ? 'Approval required' : '—'}</td>
                  <td className="nowrap">
                    {row.status === 'PENDING' && row.decisionId && (
                      <button className="ghost tiny" onClick={() => act(row, 'approve')} disabled={busy}>Approve</button>
                    )}
                    {row.status === 'APPROVED' && (
                      <button className="ghost tiny" onClick={() => act(row, 'send')} disabled={busy}>Send {row.route}</button>
                    )}
                    {row.status === 'SENT' && <span className="muted small">Sent {row.sentAt || ''}</span>}
                  </td>
                </tr>
              ))}
              {!rows.length && <tr><td colSpan="9" className="muted">{busy ? 'Loading…' : 'No sourcing events match the filters.'}</td></tr>}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  )
}

const EMPTY_PR_LINE = {
  item_code: '', description: '', quantity: '', uom: '', plant: '', estimated_unit_price: '',
  need_by_date: '2026-11-20', urgency: 'normal'
}

function ManualPr() {
  const [form, setForm] = useState({
    requester: 'buyer', department: 'Maintenance', pr_no: '', lines: [{ ...EMPTY_PR_LINE }]
  })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [submitNow, setSubmitNow] = useState(true)
  const [outcome, setOutcome] = useState(null)

  function updateLine(index, patch) {
    setForm((current) => ({
      ...current,
      lines: current.lines.map((line, lineIndex) => lineIndex === index ? { ...line, ...patch } : line)
    }))
    setErrors((current) => ({ ...current, [index]: undefined }))
  }

  function validate() {
    const next = {}
    form.lines.forEach((line, index) => {
      const row = []
      if (!line.item_code.trim()) row.push('Item code is required')
      if (!(Number(line.quantity) > 0)) row.push('Quantity must be greater than zero')
      if (!line.uom.trim()) row.push('UOM is required')
      if (!line.plant.trim()) row.push('Plant is required')
      if (!line.need_by_date) row.push('Need-by date is required')
      if (row.length) next[index] = row
    })
    setErrors(next)
    return Object.keys(next).length === 0
  }

  async function submit(event) {
    event.preventDefault()
    setError('')
    setMessage('')
    setOutcome(null)
    if (!validate()) return
    setBusy(true)
    try {
      const body = {
        requester: form.requester,
        department: form.department,
        ...(form.pr_no.trim() ? { pr_no: form.pr_no.trim() } : {}),
        lines: form.lines.map((line, index) => ({
          ...line,
          pr_line: index + 1,
          quantity: Number(line.quantity),
          estimated_unit_price: line.estimated_unit_price === '' ? null : Number(line.estimated_unit_price)
        }))
      }
      const created = await api('/prs', { method: 'POST', body: JSON.stringify(body) })
      if (submitNow) {
        const response = await api(`/prs/${created.id}/submit`, { method: 'POST' })
        setOutcome(response)
        setMessage(`PR #${created.id} submitted as run #${response.runId}. Review the workspace for approvals.`)
      } else {
        setMessage(`Draft PR #${created.id} saved. It has not been submitted to the engine.`)
      }
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  const resultLines = outcome?.result?.lines || []

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Notice message={message} />
      <Panel
        title="Manual PR intake"
        subtitle="Fallback when chat or the CSV intake is unavailable. Each row is validated before the batch is submitted."
        actions={<label className="check"><input type="checkbox" checked={submitNow} onChange={(event) => setSubmitNow(event.target.checked)} /> Submit to engine</label>}
      >
        <form className="form" onSubmit={submit}>
          <div className="form-grid">
            <label>Requester<input value={form.requester} onChange={(event) => setForm({ ...form, requester: event.target.value })} required /></label>
            <label>Department<input value={form.department} onChange={(event) => setForm({ ...form, department: event.target.value })} required /></label>
            <label>PR number<input value={form.pr_no} onChange={(event) => setForm({ ...form, pr_no: event.target.value })} placeholder="Optional, generated if blank" /></label>
          </div>
          <h3>Lines</h3>
          {form.lines.map((line, index) => (
            <div className="pr-line" key={index}>
              <div className="line-row">
                <input placeholder="Item code" value={line.item_code} onChange={(event) => updateLine(index, { item_code: event.target.value })} />
                <input placeholder="Description" value={line.description} onChange={(event) => updateLine(index, { description: event.target.value })} />
                <input type="number" min="0" step="0.01" placeholder="Quantity" value={line.quantity} onChange={(event) => updateLine(index, { quantity: event.target.value })} />
                <input placeholder="UOM" value={line.uom} onChange={(event) => updateLine(index, { uom: event.target.value })} />
                <button type="button" className="ghost tiny" onClick={() => setForm({ ...form, lines: form.lines.filter((_, i) => i !== index) })} disabled={form.lines.length === 1}>Remove</button>
              </div>
              <div className="line-row">
                <input placeholder="Plant" value={line.plant} onChange={(event) => updateLine(index, { plant: event.target.value })} />
                <input type="number" min="0" step="0.01" placeholder="Estimated unit price" value={line.estimated_unit_price} onChange={(event) => updateLine(index, { estimated_unit_price: event.target.value })} />
                <input type="date" value={line.need_by_date} onChange={(event) => updateLine(index, { need_by_date: event.target.value })} />
                <select value={line.urgency} onChange={(event) => updateLine(index, { urgency: event.target.value })}>
                  <option value="normal">Normal</option>
                  <option value="urgent">Urgent</option>
                  <option value="emergency">Emergency</option>
                </select>
                <span className="muted small">Line {index + 1}</span>
              </div>
              {errors[index]?.length > 0 && (
                <div className="row-error">{errors[index].join(' · ')}</div>
              )}
            </div>
          ))}
          <div className="actions wrap">
            <button type="button" className="secondary" onClick={() => setForm({ ...form, lines: [...form.lines, { ...EMPTY_PR_LINE }] })}>Add line</button>
            <button type="submit" disabled={busy}>{busy ? 'Submitting…' : submitNow ? 'Create and submit PR' : 'Save draft PR'}</button>
          </div>
        </form>
      </Panel>

      {outcome && (
        <Panel title={`Submitted run #${outcome.runId}`} subtitle="Per-row engine validation and routing for this PR.">
          <div className="table-wrap">
            <table>
              <thead><tr><th>PR line</th><th>Item</th><th>Match</th><th>Decision</th><th>Route</th><th>Validation</th><th>Reason</th></tr></thead>
              <tbody>
                {resultLines.map((line) => (
                  <tr key={line.prLineId} className={line.validationStatus !== 'VALID' || line.route === 'NOT_PROCESSED' ? 'row-bad' : ''}>
                    <td className="nowrap">{line.prNo}-{line.prLine}</td>
                    <td>{line.resolvedItemCode || '—'}</td>
                    <td className="small">{line.matchMethod || '—'}</td>
                    <td><Badge value={line.decision} /></td>
                    <td><Badge value={line.route} /></td>
                    <td><Badge value={line.validationStatus} /></td>
                    <td className="reason">{line.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {outcome.result?.lines?.some((line) => line.route === 'NOT_PROCESSED') && (
            <p className="warning">Some rows were not processed. Fix the row-level validation errors and submit a corrected PR.</p>
          )}
        </Panel>
      )}
    </div>
  )
}

function BuyerChat() {
  const [messages, setMessages] = useState([
    {
      role: 'assistant',
      text: 'Tell me what you need to buy, or describe a policy change. Example: “12 safety helmets for Chakan by next Friday” or “From 1 Dec, emergency limit is 3 lakh for Chakan”.',
      meta: null
    }
  ])
  const [input, setInput] = useState('')
  const [sessionId, setSessionId] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function send(event) {
    event.preventDefault()
    const text = input.trim()
    if (!text) return
    setMessages((current) => [...current, { role: 'user', text }])
    setInput('')
    setBusy(true)
    setError('')
    try {
      const response = await api('/chat/messages', {
        method: 'POST',
        body: JSON.stringify({
          session_id: sessionId,
          message: text,
          requester: 'buyer',
          department: 'Maintenance',
          today: '2026-11-16'
        })
      })
      setSessionId(response.session_id)
      setMessages((current) => [...current, { role: 'assistant', text: response.reply, meta: response }])
    } catch (cause) {
      setError(cause.message)
      setMessages((current) => [...current, { role: 'assistant', text: `I could not complete that: ${cause.message}`, meta: null }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      <Panel
        title="Buyer chat"
        subtitle="Slot-filling assistant. It drafts PRs and policy amendments; Procurement still owns approval and activation."
        actions={<span className="muted">Session {sessionId ? `#${sessionId}` : 'new'}</span>}
      >
        <ErrorBox error={error} />
        <div className="chat">
          <div className="messages">
            {messages.map((message, index) => (
              <div key={index} className={`message ${message.role}`}>
                <div className="bubble">{message.text}</div>
                {message.meta && (
                  <div className="message-meta">
                    {message.meta.stage && <span>Stage: <strong>{message.meta.stage}</strong></span>}
                    {message.meta.intent && <span>Intent: <strong>{message.meta.intent}</strong></span>}
                    {message.meta.pr_id && <span>PR: <strong>#{message.meta.pr_id}</strong></span>}
                    {message.meta.amendment_id && <span>Amendment: <strong>#{message.meta.amendment_id}</strong></span>}
                    {message.meta.missing_slots?.length > 0 && (
                      <span>Missing: <strong>{message.meta.missing_slots.join(', ')}</strong></span>
                    )}
                  </div>
                )}
                {message.meta?.diff?.changes?.length > 0 && (
                  <ul className="diff-list">
                    {message.meta.diff.changes.map((change, changeIndex) => (
                      <li key={changeIndex}>{change.plain_language}</li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
          <form className="composer" onSubmit={send}>
            <input
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="e.g. 10 nitrile gloves for Pune tomorrow"
              disabled={busy}
            />
            <button type="submit" disabled={busy || !input.trim()}>{busy ? '…' : 'Send'}</button>
          </form>
          <div className="hints">
            <button type="button" onClick={() => setInput('Need 20 hex bolt M10x40 for Pune by 25 Nov')}>PR example</button>
            <button type="button" onClick={() => setInput('From 1 Dec, emergency limit is 3 lakh for Chakan')}>Policy example</button>
            <button type="button" onClick={() => { setMessages(messages.slice(0, 1)); setSessionId(null); setInput('') }}>Reset</button>
          </div>
        </div>
      </Panel>
    </div>
  )
}

function PolicyConsole() {
  const [versions, setVersions] = useState([])
  const [version, setVersion] = useState(null)
  const [amendments, setAmendments] = useState([])
  const [selected, setSelected] = useState(null)
  const [diff, setDiff] = useState(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [form, setForm] = useState({
    effective_from: '2026-12-01',
    source: 'manual',
    raw_text: '',
    operations: '[{"type":"set_parameter","key":"spot_limit","value":100000}]'
  })
  const [policyText, setPolicyText] = useState('')
  const [extraction, setExtraction] = useState(null)
  const [extractBusy, setExtractBusy] = useState(false)

  async function loadVersions() {
    try {
      const data = await api('/policies')
      setVersions(data)
      if (!version && data.length) await openVersion(data[0].id)
    } catch (cause) {
      setError(cause.message)
    }
  }

  async function loadAmendments() {
    try {
      setAmendments(await api('/policy-amendments'))
    } catch (cause) {
      setError(cause.message)
    }
  }

  useEffect(() => {
    loadVersions()
    loadAmendments()
  }, [])

  async function openVersion(id) {
    try {
      setVersion(await api(`/policies/${id}`))
      setDiff(null)
    } catch (cause) {
      setError(cause.message)
    }
  }

  async function openAmendment(id) {
    setError('')
    setMessage('')
    try {
      const data = await api(`/policy-amendments/${id}`)
      setSelected(data)
      if (data.status === 'APPLIED' || data.status === 'APPROVED') {
        try {
          setDiff(await api(`/policy-amendments/${id}/diff`))
        } catch {
          setDiff(null)
        }
      } else {
        setDiff(null)
      }
    } catch (cause) {
      setError(cause.message)
    }
  }

  async function createAmendment(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const operations = JSON.parse(form.operations)
      const created = await api('/policy-amendments', {
        method: 'POST',
        body: JSON.stringify({
          effective_from: form.effective_from,
          source: form.source,
          raw_text: form.raw_text,
          created_by: 'procurement',
          operations
        })
      })
      setMessage(`Draft amendment #${created.id} created.`)
      await loadAmendments()
      await openAmendment(created.id)
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  async function act(path, successText) {
    if (!selected) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const data = await api(`/policy-amendments/${selected.id}${path}`, {
        method: 'POST',
        body: JSON.stringify({ actor: 'procurement' })
      })
      if (data?.changes) setDiff(data)
      setMessage(successText)
      await loadAmendments()
      await openAmendment(selected.id)
      await loadVersions()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  const rules = version?.rules || []
  const parameters = version?.parameters || []
  const changes = diff?.changes || []

  function applyExtraction(data, sourceLabel, sourceKind = 'upload') {
    setExtraction(data)
    const operations = data?.operations || []
    setForm((current) => ({
      ...current,
      effective_from: data?.effective_from || current.effective_from,
      raw_text: sourceLabel,
      source: sourceKind,
      operations: JSON.stringify(operations.length ? operations : [], null, 2)
    }))
    if (!operations.length) {
      setMessage('No operations were detected; edit the operations JSON manually before creating the draft.')
    }
  }

  async function extractPasted() {
    if (!policyText.trim()) return
    setExtractBusy(true)
    setError('')
    setMessage('')
    try {
      const data = await api('/policies/describe', {
        method: 'POST',
        body: JSON.stringify({ text: policyText, today: '2026-11-16' })
      })
      applyExtraction(data, policyText, 'manual')
      setMessage(data.summary || 'Policy text parsed.')
    } catch (cause) {
      setError(cause.message)
    } finally {
      setExtractBusy(false)
    }
  }

  async function extractPdf(event) {
    const file = event.target.files?.[0]
    if (!file) return
    const formData = new FormData()
    formData.append('file', file)
    formData.append('today', '2026-11-16')
    setExtractBusy(true)
    setError('')
    setMessage('')
    try {
      const data = await api('/policies/upload', { method: 'POST', body: formData })
      applyExtraction(data, `Uploaded ${file.name}`, 'upload')
      setMessage(data.summary || 'Policy PDF parsed.')
    } catch (cause) {
      setError(cause.message)
      setMessage('Extraction failed. Paste the text or enter operations manually.')
    } finally {
      setExtractBusy(false)
      event.target.value = ''
    }
  }

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Notice message={message} />
      <div className="split">
        <Panel title="Published versions" subtitle="Immutable, content-hashed policy snapshots.">
          <div className="list">
            {versions.map((item) => (
              <button
                key={item.id}
                className={`list-item ${version?.version?.id === item.id ? 'selected' : ''}`}
                onClick={() => openVersion(item.id)}
              >
                <div>
                  <strong>{item.label}</strong>
                  <small>Effective {item.effectiveFrom}</small>
                </div>
                <Badge value={item.status} />
              </button>
            ))}
          </div>
        </Panel>

        <Panel
          title={version ? `Policy ${version.version?.label}` : 'Policy'}
          subtitle={version ? `${rules.length} rules · ${parameters.length} parameters · ${version.facts?.length || 0} facts` : 'Select a version'}
        >
          <div className="table-wrap tall">
            <table>
              <thead>
                <tr><th>Rule</th><th className="right">Priority</th><th>When</th><th>Then</th></tr>
              </thead>
              <tbody>
                {rules.map((rule) => (
                  <tr key={rule.id || rule.ruleKey}>
                    <td className="nowrap">{rule.ruleKey}</td>
                    <td className="right">{rule.priority}</td>
                    <td className="mono small">{rule.conditionJson || rule.condition_json}</td>
                    <td className="mono small">{rule.actionJson || rule.action_json}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <details className="details">
            <summary>Parameters</summary>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Key</th><th>Scope</th><th className="right">Value</th></tr></thead>
                <tbody>
                  {parameters.map((parameter) => (
                    <tr key={parameter.id || `${parameter.paramKey}-${parameter.scopeJson}`}>
                      <td>{parameter.paramKey || parameter.param_key}</td>
                      <td className="mono small">{parameter.scopeJson || parameter.scope_json || 'global'}</td>
                      <td className="right">{parameter.valueJson || parameter.value_json}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </Panel>
      </div>

      <div className="split">
        <Panel
          title="Amendments"
          subtitle="DRAFT → VALIDATED → APPROVED/APPLIED, or REJECTED."
          actions={<button className="secondary" onClick={loadAmendments}>Refresh</button>}
        >
          <div className="list">
            {amendments.map((item) => (
              <button
                key={item.id}
                className={`list-item ${selected?.id === item.id ? 'selected' : ''}`}
                onClick={() => openAmendment(item.id)}
              >
                <div>
                  <strong>#{item.id} · {item.source}</strong>
                  <small>Effective {pick(item, 'effective_from', 'effectiveFrom') || '—'}</small>
                </div>
                <Badge value={item.status} />
              </button>
            ))}
            {!amendments.length && <p className="muted">No amendments yet.</p>}
          </div>
        </Panel>

        <Panel
          title={selected ? `Amendment #${selected.id}` : 'New amendment'}
          subtitle={selected ? `Status ${selected.status}` : 'Create a manual draft, or use Buyer chat.'}
          actions={
            selected ? (
              <>
                {['DRAFT', 'VALIDATED'].includes(selected.status) && (
                  <>
                    <button className="secondary" onClick={() => act('/validate', 'Draft validated.')} disabled={busy}>Validate</button>
                    <button className="secondary" onClick={() => act('/preview', 'Plain-language diff generated.')} disabled={busy}>Preview</button>
                    <button onClick={() => act('/approve', 'Amendment approved and applied.')} disabled={busy}>Approve & apply</button>
                    <button className="danger" onClick={() => act('/reject', 'Amendment rejected.')} disabled={busy}>Reject</button>
                  </>
                )}
                {selected.status === 'APPROVED' && (
                  <>
                    <button onClick={() => act('/activate', 'Approved amendment activated.')} disabled={busy}>Activate</button>
                    <button className="danger" onClick={() => act('/reject', 'Amendment rejected.')} disabled={busy}>Reject</button>
                  </>
                )}
                {['APPLIED', 'REJECTED'].includes(selected.status) && (
                  <span className="muted">Terminal state · no further transitions</span>
                )}
              </>
            ) : null
          }
        >
          {selected ? (
            <>
              <div className="meta-grid">
                <div><span>Source</span><strong>{selected.source}</strong></div>
                <div><span>Effective</span><strong>{pick(selected, 'effective_from', 'effectiveFrom') || '—'}</strong></div>
                <div><span>Created by</span><strong>{pick(selected, 'created_by', 'createdBy') || '—'}</strong></div>
                <div><span>Applied version</span><strong>{pick(selected, 'applied_version_id', 'appliedVersionId') || '—'}</strong></div>
              </div>
              <h3>Operations</h3>
              <pre className="code">{JSON.stringify(selected.operations, null, 2)}</pre>
              {changes.length > 0 && (
                <>
                  <h3>Plain-language diff</h3>
                  <ul className="diff-list">
                    {changes.map((change, index) => <li key={index}>{change.plain_language}</li>)}
                  </ul>
                  {diff.content_hash && <p className="muted mono small">Content hash {diff.content_hash}</p>}
                </>
              )}
              {selected.raw_text && (
                <>
                  <h3>Source text</h3>
                  <pre className="code">{selected.raw_text}</pre>
                </>
              )}
              <div className="actions wrap">
                <button
                  className="ghost"
                  onClick={async () => {
                    const target = prompt('Parameter key', 'spot_limit')
                    if (!target) return
                    const value = prompt('Value', '100000')
                    if (!value) return
                    setBusy(true)
                    try {
                      await api(`/policy-amendments/${selected.id}/parameters`, {
                        method: 'POST',
                        body: JSON.stringify({ key: target, value: Number(value), actor: 'procurement' })
                      })
                      await loadAmendments()
                      await openAmendment(selected.id)
                      setMessage('Parameter operation added; amendment reset to DRAFT.')
                    } catch (cause) {
                      setError(cause.message)
                    } finally {
                      setBusy(false)
                    }
                  }}
                  disabled={busy}
                >
                  Add parameter operation
                </button>
              </div>
            </>
          ) : (
            <form className="form" onSubmit={createAmendment}>
              <div className="intake">
                <div className="intake-head">
                  <strong>Policy intake</strong>
                  <span className="muted">PDF upload or pasted text, then review the operations before drafting.</span>
                </div>
                <textarea
                  rows="4"
                  value={policyText}
                  onChange={(event) => setPolicyText(event.target.value)}
                  placeholder="Paste the policy change text, e.g. From 1 Dec 2026, emergency limit is 3 lakh for Chakan."
                />
                <div className="actions wrap">
                  <button type="button" className="secondary" onClick={extractPasted} disabled={extractBusy || !policyText.trim()}>
                    {extractBusy ? 'Extracting…' : 'Extract pasted text'}
                  </button>
                  <label className={`button ghost ${extractBusy ? 'disabled' : ''}`}>
                    Upload policy PDF
                    <input type="file" accept="application/pdf" onChange={extractPdf} disabled={extractBusy} hidden />
                  </label>
                </div>
                {extraction && (
                  <div className="extraction">
                    <div><strong>{extraction.summary || 'Extraction ready'}</strong></div>
                    {(extraction.warnings || []).length > 0 && (
                      <div className="warning">Warnings: {extraction.warnings.join('; ')}</div>
                    )}
                    <p className="muted">
                      Operations detected: {extraction.operations?.length || 0}. Effective date and operations are
                      prefilled below; edit them if needed.
                    </p>
                  </div>
                )}
              </div>
              <label>
                Effective from
                <input
                  type="date"
                  value={form.effective_from}
                  onChange={(event) => setForm({ ...form, effective_from: event.target.value })}
                  required
                />
              </label>
              <label>
                Source note
                <input
                  value={form.raw_text}
                  onChange={(event) => setForm({ ...form, raw_text: event.target.value })}
                  placeholder="e.g. CFO memo dated 20 Nov"
                />
              </label>
              <label>
                Operations (JSON array)
                <textarea
                  rows="7"
                  value={form.operations}
                  onChange={(event) => setForm({ ...form, operations: event.target.value })}
                  spellCheck="false"
                />
              </label>
              <button type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create draft amendment'}</button>
            </form>
          )}
        </Panel>
      </div>
    </div>
  )
}

const EMPTY_CONTRACT = {
  contract_id: '', supplier_id: '', supplier_name: '', start_date: '', end_date: '',
  value_cap: '', consumed_value: '', status: 'Active',
  lines: [{ item_code: '', description: '', uom: '', rate: '' }]
}

function Contracts() {
  const [contracts, setContracts] = useState([])
  const [extraction, setExtraction] = useState(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [form, setForm] = useState(EMPTY_CONTRACT)
  const [editing, setEditing] = useState(false)

  async function load() {
    try {
      setContracts(await api('/contracts'))
    } catch (cause) {
      setError(cause.message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  function editContract(contract) {
    setForm({
      contract_id: contract.contract_id || '',
      supplier_id: contract.supplier_id || '',
      supplier_name: contract.supplier_name || '',
      start_date: contract.start_date || '',
      end_date: contract.end_date || '',
      value_cap: contract.value_cap ?? '',
      consumed_value: contract.baseline_consumed ?? '',
      status: contract.status || 'Active',
      lines: (contract.lines || []).length
        ? contract.lines.map((line) => ({
            item_code: line.item_code || '',
            description: line.description || '',
            uom: line.uom || '',
            rate: line.rate ?? ''
          }))
        : [{ item_code: '', description: '', uom: '', rate: '' }]
    })
    setEditing(true)
    setExtraction(null)
    setError('')
    setMessage('')
  }

  async function saveManual(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const payload = {
        ...form,
        value_cap: form.value_cap === '' ? null : Number(form.value_cap),
        consumed_value: form.consumed_value === '' ? null : Number(form.consumed_value),
        lines: form.lines.filter((line) => line.item_code.trim()).map((line) => ({
          ...line,
          rate: line.rate === '' ? null : Number(line.rate)
        }))
      }
      await api('/contracts', { method: 'POST', body: JSON.stringify(payload) })
      setMessage('Contract ' + form.contract_id + ' saved.')
      setForm(EMPTY_CONTRACT)
      setEditing(false)
      await load()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  function updateLine(index, patch) {
    setForm((current) => ({
      ...current,
      lines: current.lines.map((line, lineIndex) => lineIndex === index ? { ...line, ...patch } : line)
    }))
  }

  async function extract(event) {
    const file = event.target.files?.[0]
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    setBusy(true)
    setError('')
    setMessage('')
    try {
      setExtraction(await api('/contracts/upload', { method: 'POST', body: form }))
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  async function save() {
    if (!extraction) return
    setBusy(true)
    setError('')
    try {
      await api('/contracts', {
        method: 'POST',
        body: JSON.stringify({ ...extraction, source: extraction.source || 'pdf', status: 'Active' })
      })
      setMessage(`Contract ${extraction.contract_id} saved.`)
      setExtraction(null)
      await load()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      <ErrorBox error={error} />
      <Notice message={message} />
      <Panel
        title="Rate contracts"
        subtitle="Baseline consumption plus this environment's run consumption; remaining never rewrites the contract baseline."
        actions={
          <>
            <button className="secondary" onClick={() => { setEditing(true); setForm(EMPTY_CONTRACT); setExtraction(null) }}>Add contract</button>
            <label className={`button ${busy ? 'disabled' : ''}`}>
              Upload contract PDF
              <input type="file" accept="application/pdf" onChange={extract} disabled={busy} hidden />
            </label>
          </>
        }
      >
        <div className="table-wrap tall">
          <table>
            <thead>
              <tr>
                <th>Contract</th><th>Supplier</th><th>Term</th><th className="right">Cap</th>
                <th className="right">Consumed</th><th className="right">Remaining</th><th>Status</th><th>Lines</th><th></th>
              </tr>
            </thead>
            <tbody>
              {contracts.map((contract) => (
                <tr key={contract.contract_id}>
                  <td className="nowrap">{contract.contract_id}</td>
                  <td>{contract.supplier_id} · {contract.supplier_name}</td>
                  <td className="nowrap">{contract.start_date} → {contract.end_date}</td>
                  <td className="right">{money(contract.value_cap)}</td>
                  <td className="right">{money(contract.consumed)}</td>
                  <td className="right">{money(contract.remaining)}</td>
                  <td><Badge value={contract.status} /></td>
                  <td>{contract.lines?.length ?? 0}</td>
                  <td><button className="ghost tiny" onClick={() => editContract(contract)}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      {editing && (
        <Panel
          title={form.contract_id ? 'Contract ' + form.contract_id : 'New contract'}
          subtitle="Manual entry is the fallback when PDF extraction is unavailable. Saving replaces the contract lines."
          actions={<button className="secondary" onClick={() => { setEditing(false); setForm(EMPTY_CONTRACT) }}>Close</button>}
        >
          <form className="form" onSubmit={saveManual}>
            <div className="form-grid">
              <label>Contract ID<input value={form.contract_id} onChange={(event) => setForm({ ...form, contract_id: event.target.value })} required /></label>
              <label>Supplier ID<input value={form.supplier_id} onChange={(event) => setForm({ ...form, supplier_id: event.target.value })} required /></label>
              <label>Supplier name<input value={form.supplier_name} onChange={(event) => setForm({ ...form, supplier_name: event.target.value })} /></label>
              <label>Status
                <select value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value })}>
                  <option value="Active">Active</option>
                  <option value="Inactive">Inactive</option>
                </select>
              </label>
              <label>Start date<input type="date" value={form.start_date} onChange={(event) => setForm({ ...form, start_date: event.target.value })} required /></label>
              <label>End date<input type="date" value={form.end_date} onChange={(event) => setForm({ ...form, end_date: event.target.value })} required /></label>
              <label>Value cap<input type="number" step="0.01" value={form.value_cap} onChange={(event) => setForm({ ...form, value_cap: event.target.value })} required /></label>
              <label>Baseline consumed<input type="number" step="0.01" value={form.consumed_value} onChange={(event) => setForm({ ...form, consumed_value: event.target.value })} placeholder="0" /></label>
            </div>
            <h3>Rate lines</h3>
            {form.lines.map((line, index) => (
              <div className="line-row" key={index}>
                <input placeholder="Item code" value={line.item_code} onChange={(event) => updateLine(index, { item_code: event.target.value })} required />
                <input placeholder="Description" value={line.description} onChange={(event) => updateLine(index, { description: event.target.value })} />
                <input placeholder="UOM" value={line.uom} onChange={(event) => updateLine(index, { uom: event.target.value })} />
                <input type="number" step="0.01" placeholder="Rate" value={line.rate} onChange={(event) => updateLine(index, { rate: event.target.value })} required />
                <button type="button" className="ghost tiny" onClick={() => setForm({ ...form, lines: form.lines.filter((_, i) => i !== index) })} disabled={form.lines.length === 1}>Remove</button>
              </div>
            ))}
            <div className="actions wrap">
              <button type="button" className="secondary" onClick={() => setForm({ ...form, lines: [...form.lines, { item_code: '', description: '', uom: '', rate: '' }] })}>Add line</button>
              <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save contract'}</button>
            </div>
          </form>
        </Panel>
      )}


      {extraction && (
        <Panel title="Extracted contract" subtitle={extraction.document_ref} actions={
          <>
            <button onClick={save} disabled={busy}>Save contract</button>
            <button className="secondary" onClick={() => setExtraction(null)}>Discard</button>
          </>
        }>
          {extraction.warnings?.length > 0 && (
            <div className="warning">Warnings: {extraction.warnings.join('; ')}</div>
          )}
          <div className="meta-grid">
            <div><span>Contract</span><strong>{extraction.contract_id || '—'}</strong></div>
            <div><span>Supplier</span><strong>{extraction.supplier_id || '—'} · {extraction.supplier_name || ''}</strong></div>
            <div><span>Term</span><strong>{extraction.start_date || '—'} → {extraction.end_date || '—'}</strong></div>
            <div><span>Value cap</span><strong>{money(extraction.value_cap)}</strong></div>
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Item</th><th>Description</th><th>UOM</th><th className="right">Rate</th></tr></thead>
              <tbody>
                {(extraction.lines || []).map((line) => (
                  <tr key={line.item_code}>
                    <td className="nowrap">{line.item_code}</td>
                    <td>{line.description}</td>
                    <td>{line.uom}</td>
                    <td className="right">{money(line.rate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
    </div>
  )
}

function PreferredSuppliers() {
  const [asOf, setAsOf] = useState('2026-11-01')
  const [itemCode, setItemCode] = useState('ITM-1036')
  const [rows, setRows] = useState([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function load() {
    setBusy(true)
    setError('')
    try {
      const data = await api(`/preferred-suppliers?as_of_date=${asOf}&item_code=${encodeURIComponent(itemCode)}`)
      const flattened = Object.entries(data).flatMap(([code, suppliers]) =>
        suppliers.map((supplier) => ({ code, ...supplier }))
      )
      setRows(flattened)
    } catch (cause) {
      setError(cause.message)
      setRows([])
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  return (
    <div className="stack">
      <Panel
        title="Preferred suppliers"
        subtitle="Ranked by recency, PO frequency, contract coverage, on-time delivery, and quality. Active suppliers with item history or an effective contract."
        actions={
          <a className="button ghost" href={`/api/preferred-suppliers/export.csv?as_of_date=${asOf}${itemCode ? `&item_code=${encodeURIComponent(itemCode)}` : ''}`}>
            Export top three
          </a>
        }
      >
        <ErrorBox error={error} />
        <div className="inline-form">
          <label>As of <input type="date" value={asOf} onChange={(event) => setAsOf(event.target.value)} /></label>
          <label>Item code <input value={itemCode} onChange={(event) => setItemCode(event.target.value)} placeholder="Blank = all" /></label>
          <button onClick={load} disabled={busy}>{busy ? 'Loading…' : 'Rank'}</button>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Item</th><th className="right">Rank</th><th>Supplier</th><th className="right">Score</th><th>Recency</th><th className="right">POs</th><th>Contract</th><th className="right">On-time</th><th className="right">Quality</th><th className="right">Reference price</th><th>Reference source</th></tr></thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.code}-${row.supplierId}`}>
                  <td className="nowrap">{row.code}</td>
                  <td className="right">{row.rank}</td>
                  <td>{row.supplierId}</td>
                  <td className="right">{row.score}</td>
                  <td>{row.recencyBucket}</td>
                  <td className="right">{row.poCount}</td>
                  <td>{row.hasContract ? 'Yes' : 'No'}</td>
                  <td className="right">{row.scoreComponents?.on_time_pct ?? '—'}%</td>
                  <td className="right">{row.scoreComponents?.quality_rating ?? '—'}/5</td>
                  <td className="right">{money(row.referencePrice)}</td>
                  <td className="small">{row.referenceSource || '—'}</td>
                </tr>
              ))}
              {!rows.length && <tr><td colSpan="11" className="muted">{busy ? 'Ranking…' : 'No suppliers returned.'}</td></tr>}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  )
}

function AuditTrail() {
  const [logs, setLogs] = useState([])
  const [error, setError] = useState('')

  useEffect(() => {
    api('/audit').then(setLogs).catch((cause) => setError(cause.message))
  }, [])

  return (
    <div className="stack">
      <Panel title="Audit trail" subtitle="Every batch, policy transition, override, and chat action is recorded.">
        <ErrorBox error={error} />
        <div className="table-wrap tall">
          <table>
            <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th><th>Before → after</th></tr></thead>
            <tbody>
              {logs.map((log) => (
                <tr key={log.id}>
                  <td className="nowrap">{log.createdAt ? String(log.createdAt).slice(0, 19).replace('T', ' ') : '—'}</td>
                  <td>{log.actor || '—'}</td>
                  <td><Badge value={log.action} /></td>
                  <td className="nowrap">{log.target || '—'}</td>
                  <td className="mono small">
                    {log.beforeJson || log.afterJson
                      ? `${log.beforeJson || '—'} → ${log.afterJson || '—'}`
                      : '—'}
                  </td>
                </tr>
              ))}
              {!logs.length && <tr><td colSpan="5" className="muted">No audit records yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  )
}

export default App
