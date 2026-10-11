CREATE TABLE IF NOT EXISTS claims (
  id UUID PRIMARY KEY,
  employee_id VARCHAR(128) NOT NULL,
  title VARCHAR(256) NOT NULL DEFAULT 'My reimbursement',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  currency VARCHAR(8) NOT NULL DEFAULT 'INR',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  submitted_at TIMESTAMP NULL,
  mock_reference VARCHAR(64) NULL
);

CREATE TABLE IF NOT EXISTS expense_items (
  id UUID PRIMARY KEY,
  claim_id UUID NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  vendor VARCHAR(256) NULL,
  expense_date DATE NULL,
  amount NUMERIC(12,2) NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'INR',
  category VARCHAR(64) NULL,
  description VARCHAR(512) NULL,
  source_file VARCHAR(256) NULL,
  confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  needs_review BOOLEAN NOT NULL DEFAULT FALSE,
  duplicate_of UUID NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_expense_claim ON expense_items(claim_id);

CREATE TABLE IF NOT EXISTS receipt_docs (
  id UUID PRIMARY KEY,
  claim_id UUID NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  file_name VARCHAR(256) NOT NULL,
  content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
  file_hash VARCHAR(64) NOT NULL,
  ocr_text TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_receipt_claim ON receipt_docs(claim_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_receipt_claim_hash ON receipt_docs(claim_id, file_hash);
