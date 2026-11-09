-- Additive/idempotent MySQL migration for the simulated FX market.
-- Run against the intended dmw schema after backup; this file is NOT run automatically.
-- No existing player funds, inventories or historical tables are changed.
CREATE TABLE IF NOT EXISTS economy_market_state (
    id INT NOT NULL PRIMARY KEY,
    tick_sequence BIGINT NOT NULL DEFAULT 0,
    trading_enabled BOOLEAN NULL,
    entropy_key VARCHAR(64) NOT NULL,
    net_issued DECIMAL(19,2) NOT NULL DEFAULT 0,
    fees DECIMAL(19,2) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Also supports a locally initialized market schema from before the administrator switch.
SET @dmw_fx_switch_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_market_state' AND column_name='trading_enabled')=0,
    'ALTER TABLE economy_market_state ADD COLUMN trading_enabled BOOLEAN NULL','SELECT 1');
PREPARE dmw_fx_switch_stmt FROM @dmw_fx_switch_ddl;
EXECUTE dmw_fx_switch_stmt;
DEALLOCATE PREPARE dmw_fx_switch_stmt;

CREATE TABLE IF NOT EXISTS economy_market_quote (
    id BIGINT NOT NULL PRIMARY KEY,
    quoted_at DATETIME(6) NOT NULL,
    eur_usd DECIMAL(24,8) NOT NULL,
    gbp_usd DECIMAL(24,8) NOT NULL,
    usd_jpy DECIMAL(24,8) NOT NULL,
    usd_chf DECIMAL(24,8) NOT NULL,
    UNIQUE KEY uk_market_quote_minute (quoted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS economy_market_position (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id VARCHAR(96) NOT NULL,
    user_id BIGINT NOT NULL,
    pair ENUM('EUR_USD','GBP_USD','USD_JPY','USD_CHF') NOT NULL,
    side ENUM('LONG','SHORT') NOT NULL,
    status ENUM('OPEN','CLOSED','LIQUIDATED') NOT NULL,
    margin DECIMAL(19,2) NOT NULL,
    leverage INT NOT NULL,
    notional DECIMAL(19,2) NOT NULL,
    opening_fee DECIMAL(19,2) NOT NULL,
    entry_price DECIMAL(24,8) NOT NULL,
    entry_quote BIGINT NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    exit_price DECIMAL(24,8) NULL,
    exit_quote BIGINT NULL,
    closed_at DATETIME(6) NULL,
    pnl DECIMAL(19,2) NULL,
    new_debt DECIMAL(19,2) NULL,
    debt_repaid DECIMAL(19,2) NULL,
    payout DECIMAL(19,2) NULL,
    extra_paid DECIMAL(19,2) NULL,
    close_receipt VARCHAR(1000) NULL,
    UNIQUE KEY uk_market_position_operation (operation_id),
    INDEX idx_market_position_owner (user_id,status),
    INDEX idx_market_position_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS economy_market_receipt (
    operation_id VARCHAR(96) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    position_id BIGINT NULL,
    amount DECIMAL(19,2) NOT NULL,
    debt DECIMAL(19,2) NOT NULL,
    display_text VARCHAR(1000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive migration for margin calls; existing positions retain their original margin/exposure.
CREATE TABLE IF NOT EXISTS economy_market_notice (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    trigger_quote BIGINT NULL,
    message VARCHAR(1200) NOT NULL,
    message_id VARCHAR(255) NULL,
    INDEX idx_market_notice_status (status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @dmw_fx_column = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_market_position' AND column_name='origin_group_id')=0,
    'ALTER TABLE economy_market_position ADD COLUMN origin_group_id BIGINT NULL','SELECT 1');
PREPARE dmw_fx_column_stmt FROM @dmw_fx_column;
EXECUTE dmw_fx_column_stmt;
DEALLOCATE PREPARE dmw_fx_column_stmt;

SET @dmw_fx_column = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_market_position' AND column_name='risk_warned')=0,
    'ALTER TABLE economy_market_position ADD COLUMN risk_warned BOOLEAN NOT NULL DEFAULT false','SELECT 1');
PREPARE dmw_fx_column_stmt FROM @dmw_fx_column;
EXECUTE dmw_fx_column_stmt;
DEALLOCATE PREPARE dmw_fx_column_stmt;

SET @dmw_fx_column = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_market_position' AND column_name='margin_call_quote')=0,
    'ALTER TABLE economy_market_position ADD COLUMN margin_call_quote BIGINT NULL','SELECT 1');
PREPARE dmw_fx_column_stmt FROM @dmw_fx_column;
EXECUTE dmw_fx_column_stmt;
DEALLOCATE PREPARE dmw_fx_column_stmt;

SET @dmw_fx_column = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_market_position' AND column_name='margin_call_deadline')=0,
    'ALTER TABLE economy_market_position ADD COLUMN margin_call_deadline DATETIME(6) NULL','SELECT 1');
PREPARE dmw_fx_column_stmt FROM @dmw_fx_column;
EXECUTE dmw_fx_column_stmt;
DEALLOCATE PREPARE dmw_fx_column_stmt;

-- Preserve the complete existing ENUM, including values introduced by previous releases.
SET @dmw_fx_reason = (SELECT column_type FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='economy_ledger_entry' AND column_name='reason');
SET @dmw_fx_extended = @dmw_fx_reason;
SET @dmw_fx_extended = IF(@dmw_fx_extended LIKE 'enum(%' AND LOCATE('''MARKET_MARGIN''',@dmw_fx_extended)=0,
    CONCAT(LEFT(@dmw_fx_extended,CHAR_LENGTH(@dmw_fx_extended)-1),',''MARKET_MARGIN'')'),@dmw_fx_extended);
SET @dmw_fx_extended = IF(@dmw_fx_extended LIKE 'enum(%' AND LOCATE('''MARKET_FEE''',@dmw_fx_extended)=0,
    CONCAT(LEFT(@dmw_fx_extended,CHAR_LENGTH(@dmw_fx_extended)-1),',''MARKET_FEE'')'),@dmw_fx_extended);
SET @dmw_fx_extended = IF(@dmw_fx_extended LIKE 'enum(%' AND LOCATE('''MARKET_SETTLEMENT''',@dmw_fx_extended)=0,
    CONCAT(LEFT(@dmw_fx_extended,CHAR_LENGTH(@dmw_fx_extended)-1),',''MARKET_SETTLEMENT'')'),@dmw_fx_extended);
SET @dmw_fx_extended = IF(@dmw_fx_extended LIKE 'enum(%' AND LOCATE('''MARKET_DEBT''',@dmw_fx_extended)=0,
    CONCAT(LEFT(@dmw_fx_extended,CHAR_LENGTH(@dmw_fx_extended)-1),',''MARKET_DEBT'')'),@dmw_fx_extended);
SET @dmw_fx_extended = IF(@dmw_fx_extended LIKE 'enum(%' AND LOCATE('''MARKET_REPAY''',@dmw_fx_extended)=0,
    CONCAT(LEFT(@dmw_fx_extended,CHAR_LENGTH(@dmw_fx_extended)-1),',''MARKET_REPAY'')'),@dmw_fx_extended);
SET @dmw_fx_ddl = IF(@dmw_fx_reason <> @dmw_fx_extended,
    CONCAT('ALTER TABLE economy_ledger_entry MODIFY COLUMN reason ',@dmw_fx_extended,' NOT NULL'),'SELECT 1');
PREPARE dmw_fx_stmt FROM @dmw_fx_ddl;
EXECUTE dmw_fx_stmt;
DEALLOCATE PREPARE dmw_fx_stmt;
