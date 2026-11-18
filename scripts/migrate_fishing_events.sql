-- Apply after migrate_fishing_cast.sql, with the bot stopped. Repeatable; no history/ledger changes.

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'economy_fishing_event' AND column_name = 'legacy_kind'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN legacy_kind VARCHAR(40) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'economy_fishing_event' AND column_name = 'amount_min'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN amount_min DECIMAL(19,2) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'economy_fishing_event' AND column_name = 'amount_max'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN amount_max DECIMAL(19,2) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'economy_fishing_event' AND column_name = 'amount_step'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN amount_step DECIMAL(19,2) NULL DEFAULT 0.01');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'economy_fishing_cast' AND column_name = 'cast_receipt'),
    'SELECT 1', 'ALTER TABLE economy_fishing_cast ADD COLUMN cast_receipt TEXT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS economy_fishing_text (
    code VARCHAR(120) NOT NULL PRIMARY KEY,
    owner_code VARCHAR(40) NOT NULL DEFAULT '*',
    scene VARCHAR(40) NOT NULL,
    template TEXT NOT NULL,
    weight INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    KEY idx_fishing_text_scene (owner_code, scene, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Legacy fields are retained as original-value backups. Defaults permit new definitions to omit them.
ALTER TABLE economy_fishing_event
    MODIFY penalty DECIMAL(19,2) NOT NULL DEFAULT 0,
    MODIFY jail_minutes INT NOT NULL DEFAULT 10,
    MODIFY strength_min INT NOT NULL DEFAULT 60,
    MODIFY strength_max INT NOT NULL DEFAULT 100,
    MODIFY reward_min DECIMAL(19,2) NOT NULL DEFAULT 800,
    MODIFY reward_max DECIMAL(19,2) NOT NULL DEFAULT 2000,
    MODIFY win_injury_percent INT NOT NULL DEFAULT 20,
    MODIFY light_percent INT NOT NULL DEFAULT 50,
    MODIFY medium_percent INT NOT NULL DEFAULT 35;

START TRANSACTION;
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT CONCAT('migrated.', e.code, '.encounter'), e.code, 'ENCOUNTER', e.encounter_template, 1, TRUE
FROM economy_fishing_event e
WHERE e.encounter_template IS NOT NULL AND TRIM(e.encounter_template) <> ''
AND NOT EXISTS (SELECT 1 FROM economy_fishing_text t WHERE t.owner_code=e.code AND t.scene='ENCOUNTER');

INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT CONCAT('migrated.', e.code, '.description'), e.code, 'DESCRIPTION', e.description, 1, TRUE
FROM economy_fishing_event e
WHERE e.description IS NOT NULL AND TRIM(e.description) <> ''
AND NOT EXISTS (SELECT 1 FROM economy_fishing_text t WHERE t.owner_code=e.code AND t.scene='DESCRIPTION');

UPDATE economy_fishing_event
SET legacy_kind = COALESCE(legacy_kind, kind),
    amount_min = CASE kind WHEN 'FINE' THEN -penalty WHEN 'TEXT' THEN 0 WHEN 'JAIL' THEN 0 ELSE reward_min END,
    amount_max = CASE kind WHEN 'FINE' THEN -penalty WHEN 'TEXT' THEN 0 WHEN 'JAIL' THEN 0 ELSE reward_max END,
    amount_step = CASE WHEN amount_min=TRUNCATE(amount_min,0) AND amount_max=TRUNCATE(amount_max,0) THEN 1 ELSE 0.01 END,
    kind = CASE WHEN kind IN ('CROCODILE','COMBAT') THEN 'COMBAT' WHEN kind='JAIL' THEN 'JAIL' ELSE 'MONEY' END
WHERE kind IN ('FINE','FUN','REWARD','TEXT','CROCODILE')
   OR (kind IN ('COMBAT','JAIL') AND (amount_min IS NULL OR amount_max IS NULL));

-- Preserve unassigned legacy groups using the original category, never the unified MONEY category.
UPDATE economy_fishing_event SET pool_code =
    CASE COALESCE(legacy_kind,kind)
      WHEN 'FINE' THEN 'NEGATIVE' WHEN 'JAIL' THEN 'NEGATIVE'
      WHEN 'CROCODILE' THEN 'COMBAT' WHEN 'COMBAT' THEN 'COMBAT'
      WHEN 'TEXT' THEN 'FUN' ELSE COALESCE(legacy_kind,kind) END
WHERE pool_code IS NULL;
COMMIT;

SELECT code, kind, legacy_kind, pool_code, amount_min, amount_max, amount_step
FROM economy_fishing_event ORDER BY code;
-- Stop for review if any row appears here. Do not start a bot with partially converted definitions.
SELECT code, kind FROM economy_fishing_event
WHERE kind NOT IN ('MONEY','COMBAT','JAIL') OR amount_min IS NULL OR amount_max IS NULL;
