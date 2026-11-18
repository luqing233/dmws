-- Run against the DMW MySQL schema before starting the new JAR with ddl-auto=validate.
-- Additive and repeatable. The application seeds missing rules and links existing events on startup.
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'fish' AND column_name = 'enabled'),
    'SELECT 1', 'ALTER TABLE fish ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
           AND table_name = 'fish' AND index_name = 'idx_fish_active_level'),
    'SELECT 1', 'CREATE INDEX idx_fish_active_level ON fish (enabled, level, price)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'economy_fishing_event' AND column_name = 'pool_code'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN pool_code VARCHAR(40) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'economy_fishing_event' AND column_name = 'encounter_template'),
    'SELECT 1', 'ALTER TABLE economy_fishing_event ADD COLUMN encounter_template VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS economy_fishing_bait_rule (
    quality VARCHAR(16) NOT NULL PRIMARY KEY,
    success_bonus_bps INT NOT NULL,
    high_fish_weight_bps INT NOT NULL,
    special_multiplier_bps INT NOT NULL,
    special_cap_bps INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS economy_fishing_pool_rule (
    bait_quality VARCHAR(16) NOT NULL,
    pool_order INT NOT NULL,
    pool_code VARCHAR(40) NOT NULL,
    ticket_budget INT NULL,
    ticket_scale_bps INT NOT NULL,
    PRIMARY KEY (bait_quality, pool_order),
    CONSTRAINT fk_fishing_pool_bait FOREIGN KEY (bait_quality) REFERENCES economy_fishing_bait_rule (quality)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS economy_fishing_cast (
    operation_id VARCHAR(160) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(255) NULL,
    bot_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    group_name VARCHAR(255) NULL,
    message_id INT NULL,
    rod_level INT NOT NULL,
    bait_quality VARCHAR(16) NOT NULL,
    default_bait BOOLEAN NOT NULL,
    cast_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    failures INT NOT NULL,
    random_seed VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL,
    receipt TEXT NULL,
    KEY idx_fishing_cast_player (user_id, cast_at),
    KEY idx_fishing_cast_active (user_id, status),
    KEY idx_fishing_cast_due (status, next_attempt_at),
    KEY idx_fishing_cast_notice (status, bot_id, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name = 'economy_fishing_cast' AND column_name = 'random_seed'),
    'SELECT 1', 'ALTER TABLE economy_fishing_cast ADD COLUMN random_seed VARCHAR(64) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE economy_fishing_cast SET random_seed = HEX(RANDOM_BYTES(32))
WHERE status = 'WAITING' AND random_seed IS NULL;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
           AND table_name = 'economy_fishing_attempt' AND index_name = 'idx_fishing_attempt_group_time'),
    'SELECT 1', 'CREATE INDEX idx_fishing_attempt_group_time ON economy_fishing_attempt (group_id, occurred_at)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
