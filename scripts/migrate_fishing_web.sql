-- Additive: existing fish, catches, accounts and ledger entries are never modified.
-- Seed identity links are filled from original built-in names by the initializer, without changing gameplay fields.
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='fish' AND column_name='seed_name'), 'SELECT 1',
    'ALTER TABLE fish ADD COLUMN seed_name VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='fish' AND index_name='uk_fish_seed_name'), 'SELECT 1',
    'CREATE UNIQUE INDEX uk_fish_seed_name ON fish (seed_name)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS economy_fish_review_lock (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO economy_fish_review_lock (id) VALUES (1);

CREATE TABLE IF NOT EXISTS economy_fish_submission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NULL,
    request_id VARCHAR(36) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    author VARCHAR(40) NOT NULL,
    submission_type VARCHAR(16) NOT NULL DEFAULT 'FISH',
    event_definition TEXT NULL,
    source_fish_id INT NULL,
    source_event_code VARCHAR(40) NULL,
    reviewed_enabled BOOLEAN NULL,
    published_event_code VARCHAR(40) NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    level INT NOT NULL,
    price INT NOT NULL,
    difficulty INT NOT NULL,
    dimensions_min INT NOT NULL,
    dimensions_max INT NOT NULL,
    special BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reviewed_at DATETIME(6) NULL,
    reviewer VARCHAR(64) NULL,
    review_note VARCHAR(500) NULL,
    published_fish_id INT NULL,
    reviewed_definition TEXT NULL,
    decision_request_id VARCHAR(36) NULL,
    decision_hash VARCHAR(64) NULL,
    UNIQUE KEY uk_fish_submission_request (request_id),
    UNIQUE KEY uk_fish_submission_decision (decision_request_id),
    KEY idx_fish_submission_status (status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
