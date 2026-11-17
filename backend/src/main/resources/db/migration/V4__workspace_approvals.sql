ALTER TABLE requirements ADD COLUMN engine_requirement_id BIGINT;

ALTER TABLE decisions ADD COLUMN approval_status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED';
ALTER TABLE decisions ADD COLUMN approved_by VARCHAR(128);
ALTER TABLE decisions ADD COLUMN approved_at TIMESTAMP;
ALTER TABLE decisions ADD COLUMN po_id VARCHAR(32);
ALTER TABLE decisions ADD COLUMN requires_capex_approval BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE decisions SET approval_status = 'NOT_APPLICABLE' WHERE route = 'NOT_PROCESSED';

ALTER TABLE pos ADD COLUMN approval_status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED';
ALTER TABLE pos ADD COLUMN approved_by VARCHAR(128);
ALTER TABLE pos ADD COLUMN approved_at TIMESTAMP;

ALTER TABLE sourcing_events ADD COLUMN approved_by VARCHAR(128);
ALTER TABLE sourcing_events ADD COLUMN approved_at TIMESTAMP;
ALTER TABLE sourcing_events ADD COLUMN sent_by VARCHAR(128);
ALTER TABLE sourcing_events ADD COLUMN sent_at TIMESTAMP;

CREATE INDEX idx_decisions_po ON decisions (po_id);
CREATE INDEX idx_sourcing_requirement ON sourcing_events (requirement_id);
