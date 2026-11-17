ALTER TABLE decisions ADD COLUMN original_decision_json TEXT;
ALTER TABLE decisions ADD COLUMN override_comment TEXT;
ALTER TABLE decisions ADD COLUMN overridden_by VARCHAR(128);
ALTER TABLE decisions ADD COLUMN overridden_at TIMESTAMP;
