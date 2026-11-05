-- Additive/idempotent MySQL migration. USE the live dmw database only after backup.
-- Run before the new jar. No old game table is recreated; no existing balance is changed.
-- Extend native MySQL ENUM if present; VARCHAR-based installations already support new reasons.
SET @dmw_reason_type = (SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='economy_ledger_entry' AND column_name='reason');
SET @dmw_reason_extended = IF(@dmw_reason_type LIKE 'enum(%' AND LOCATE('''HOSPITAL_FEE''', @dmw_reason_type)=0, CONCAT(LEFT(@dmw_reason_type, CHAR_LENGTH(@dmw_reason_type)-1), ',''HOSPITAL_FEE'')'), @dmw_reason_type);
SET @dmw_reason_extended = IF(@dmw_reason_extended LIKE 'enum(%' AND LOCATE('''BAIL_PAYMENT''', @dmw_reason_extended)=0, CONCAT(LEFT(@dmw_reason_extended, CHAR_LENGTH(@dmw_reason_extended)-1), ',''BAIL_PAYMENT'')'), @dmw_reason_extended);
SET @dmw_adventure_ddl = IF(@dmw_reason_type <> @dmw_reason_extended, CONCAT('ALTER TABLE economy_ledger_entry MODIFY COLUMN reason ', @dmw_reason_extended, ' NOT NULL'), 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;
CREATE TABLE IF NOT EXISTS economy_player_condition (
    user_id BIGINT NOT NULL PRIMARY KEY,
    crime INT NOT NULL DEFAULT 0,
    crime_decay_on DATE NULL,
    injury INT NOT NULL DEFAULT 0,
    hospital_since DATETIME(6) NULL,
    hospital_until DATETIME(6) NULL,
    hospital_session VARCHAR(36) NULL,
    diagnosis_fee DECIMAL(19,2) NULL,
    hourly_fee DECIMAL(19,2) NULL,
    jail_until DATETIME(6) NULL,
    last_hospital_fee DECIMAL(19,2) NULL,
    last_medical_loan DECIMAL(19,2) NULL,
    last_discharged_at DATETIME(6) NULL,
    INDEX idx_condition_hospital_until (hospital_until),
    INDEX idx_condition_jail_until (jail_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_user_profile' AND column_name = 'monthly_sign_expires_on') = 0, 'ALTER TABLE economy_user_profile ADD COLUMN monthly_sign_expires_on DATE NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;

SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_rob_record' AND column_name = 'outcome') = 0, 'ALTER TABLE economy_rob_record ADD COLUMN outcome VARCHAR(32) NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;

SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_rob_record' AND column_name = 'injury') = 0, 'ALTER TABLE economy_rob_record ADD COLUMN injury INT NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;

SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_rob_record' AND column_name = 'injured_user_id') = 0, 'ALTER TABLE economy_rob_record ADD COLUMN injured_user_id BIGINT NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;

SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_rob_record' AND column_name = 'crime_after') = 0, 'ALTER TABLE economy_rob_record ADD COLUMN crime_after INT NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;

SET @dmw_adventure_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'economy_rob_record' AND column_name = 'operation_id') = 0, 'ALTER TABLE economy_rob_record ADD COLUMN operation_id VARCHAR(80) NULL', 'SELECT 1');
PREPARE dmw_adventure_stmt FROM @dmw_adventure_ddl;
EXECUTE dmw_adventure_stmt;
DEALLOCATE PREPARE dmw_adventure_stmt;
