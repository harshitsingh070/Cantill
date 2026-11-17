ALTER TABLE chat_sessions ADD COLUMN state_json TEXT;

CREATE INDEX idx_contract_consumption_contract ON contract_consumption_runs (contract_id);
CREATE INDEX idx_contract_consumption_run ON contract_consumption_runs (run_id);
