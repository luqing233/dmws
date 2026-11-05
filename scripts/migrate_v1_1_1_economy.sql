-- HISTORICAL INITIAL IMPORT ONLY: DO NOT RERUN ON AN ACTIVE ECONOMY.
-- This script overwrites profiles and inventories with the old snapshot.
-- For the audited 2026-11-04 reconciliation use reconcile_legacy_economy.py.
-- hy_economy tables may already have been retired into dmw.economy_history_*.

-- DMWS economy migration, current for 1.1.1
-- Source: server MySQL databases `economy` and `hy_economy`
-- Target: primary `dmw` database
-- The application should be stopped while this script runs.

SET NAMES utf8mb4;
SET @migration_name = 'dmws-economy-migration-1.1.1';

CREATE TABLE IF NOT EXISTS dmw.economy_migration_log (
    step_name VARCHAR(80) NOT NULL,
    source_rows BIGINT NOT NULL DEFAULT 0,
    target_rows BIGINT NOT NULL DEFAULT 0,
    executed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (step_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

-- Wallet balances from the historical HuYan economy ledger.
INSERT INTO dmw.economy_account
    (account_key, scope, owner_id, balance, version, created_at, updated_at)
SELECT
    src.uuid,
    'WALLET',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    GREATEST(ROUND(src.balance, 2), 0.00),
    0,
    NOW(),
    NOW()
FROM economy.economy_balance_record src
WHERE src.context = 'cn.chahuyun.HuYanEconomy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND NOT EXISTS (
      SELECT 1
      FROM dmw.economy_account target
      WHERE target.account_key = src.uuid
        AND target.scope = 'WALLET'
  );

-- Opening ledger entries. They are inserted only once.
INSERT INTO dmw.economy_operation
    (operation_id, operation_type, actor_id, description, created_at)
SELECT
    CONCAT('migration:v1.1.0:wallet:', src.uuid),
    'MIGRATION_OPENING_BALANCE',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    'Migrate HuYan wallet balance',
    NOW()
FROM economy.economy_balance_record src
WHERE src.context = 'cn.chahuyun.HuYanEconomy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
ON DUPLICATE KEY UPDATE operation_id = operation_id;

INSERT INTO dmw.economy_ledger_entry
    (operation_id, account_key, scope, delta, balance_after, reason,
     actor_id, description, created_at)
SELECT
    CONCAT('migration:v1.1.0:wallet:', src.uuid),
    src.uuid,
    'WALLET',
    GREATEST(ROUND(src.balance, 2), 0.00),
    GREATEST(ROUND(src.balance, 2), 0.00),
    'OPENING_BALANCE',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    'Migrate HuYan wallet balance',
    NOW()
FROM economy.economy_balance_record src
WHERE src.context = 'cn.chahuyun.HuYanEconomy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND EXISTS (
      SELECT 1
      FROM dmw.economy_account target
      WHERE target.account_key = src.uuid
        AND target.scope = 'WALLET'
        AND ABS(target.balance - GREATEST(ROUND(src.balance, 2), 0.00)) < 0.001
  )
  AND NOT EXISTS (
      SELECT 1
      FROM dmw.economy_ledger_entry e
      WHERE e.operation_id = CONCAT('migration:v1.1.0:wallet:', src.uuid)
  );

-- Main bank balances from the historical global economy context.
INSERT INTO dmw.economy_account
    (account_key, scope, owner_id, balance, version, created_at, updated_at)
SELECT
    src.uuid,
    'MAIN_BANK',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    GREATEST(ROUND(src.balance, 2), 0.00),
    0,
    NOW(),
    NOW()
FROM economy.economy_balance_record src
LEFT JOIN dmw.economy_account target
    ON target.account_key = src.uuid
   AND target.scope = 'MAIN_BANK'
WHERE src.context = 'global-economy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND target.id IS NULL;

-- Main bank opening ledger entries are inserted only once.
INSERT INTO dmw.economy_operation
    (operation_id, operation_type, actor_id, description, created_at)
SELECT
    CONCAT('migration:v1.1.1:bank:', src.uuid),
    'MIGRATION_OPENING_BALANCE',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    'Migrate HuYan main bank balance',
    NOW()
FROM economy.economy_balance_record src
JOIN dmw.economy_account target
    ON target.account_key = src.uuid
   AND target.scope = 'MAIN_BANK'
WHERE src.context = 'global-economy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND ABS(target.balance - GREATEST(ROUND(src.balance, 2), 0.00)) < 0.001
ON DUPLICATE KEY UPDATE operation_id = operation_id;

INSERT INTO dmw.economy_ledger_entry
    (operation_id, account_key, scope, delta, balance_after, reason,
     actor_id, description, created_at)
SELECT
    CONCAT('migration:v1.1.1:bank:', src.uuid),
    src.uuid,
    'MAIN_BANK',
    GREATEST(ROUND(src.balance, 2), 0.00),
    GREATEST(ROUND(src.balance, 2), 0.00),
    'OPENING_BALANCE',
    CAST(SUBSTRING(src.uuid, 2) AS UNSIGNED),
    'Migrate HuYan main bank balance',
    NOW()
FROM economy.economy_balance_record src
JOIN dmw.economy_account target
    ON target.account_key = src.uuid
   AND target.scope = 'MAIN_BANK'
WHERE src.context = 'global-economy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND ABS(target.balance - GREATEST(ROUND(src.balance, 2), 0.00)) < 0.001
  AND NOT EXISTS (
      SELECT 1
      FROM dmw.economy_ledger_entry e
      WHERE e.operation_id = CONCAT('migration:v1.1.1:bank:', src.uuid)
  );

-- User profile, sign-in, active title, robbery cooldown and fishing rod.
INSERT INTO dmw.economy_user_profile
    (user_id, nickname, last_sign_date, sign_streak, old_sign_number, total_sign_count,
     total_earned, active_title_code, last_raffle_at, last_ten_raffle_at,
     fishing_rod_level, fishing_rod_purchased, last_fished_at, last_rob_at,
     default_private_bank_code, register_group_id, register_group_name,
     created_at, updated_at)
SELECT
    u.qq,
    COALESCE(NULLIF(u.name, ''), CONCAT('用户', u.qq)),
    DATE(u.signTime),
    GREATEST(COALESCE(u.signNumber, 0), 0),
    GREATEST(COALESCE(u.oldSignNumber, 0), 0),
    GREATEST(COALESCE(u.signNumber, 0), 0),
    ROUND(COALESCE(u.signEarnings, 0), 2),
    (
        SELECT t.code
        FROM hy_economy.titleinfo t
        WHERE t.userId = u.qq AND t.status = 1
        ORDER BY t.id
        LIMIT 1
    ),
    NULL,
    NULL,
    GREATEST(COALESCE(f.rodLevel, 1), 1),
    CASE
        WHEN COALESCE(f.fishRod, 0) <> 0 OR COALESCE(f.isFishRod, 0) <> 0 THEN 1
        ELSE 0
    END,
    NULL,
    r.nowTime,
    NULL,
    NULLIF(u.registerGroup, 0),
    NULL,
    NOW(),
    NOW()
FROM hy_economy.userinfo u
LEFT JOIN hy_economy.fishinfo f ON f.qq = u.qq
LEFT JOIN hy_economy.robinfo r ON r.userId = u.qq
WHERE u.qq IS NOT NULL
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    last_sign_date = VALUES(last_sign_date),
    sign_streak = VALUES(sign_streak),
    old_sign_number = VALUES(old_sign_number),
    total_sign_count = GREATEST(total_sign_count, VALUES(total_sign_count)),
    total_earned = VALUES(total_earned),
    active_title_code = COALESCE(VALUES(active_title_code), active_title_code),
    last_rob_at = COALESCE(VALUES(last_rob_at), last_rob_at),
    fishing_rod_level = GREATEST(fishing_rod_level, VALUES(fishing_rod_level)),
    fishing_rod_purchased = GREATEST(fishing_rod_purchased, VALUES(fishing_rod_purchased)),
    register_group_id = COALESCE(register_group_id, VALUES(register_group_id)),
    updated_at = NOW();

-- Item definitions required by migrated backpacks.
INSERT INTO dmw.economy_item_definition
    (code, name, description, price, effect_type, effect_value, effect_text, enabled)
SELECT
    grouped.mapped_code,
    grouped.item_name,
    grouped.item_description,
    grouped.item_price,
    CASE grouped.mapped_code
        WHEN 'double-sign-card' THEN 'SIGN_BONUS'
        WHEN 'triple-sign-card' THEN 'SIGN_BONUS'
        WHEN 'sign-monthly-card' THEN 'SIGN_BONUS'
        WHEN 'sign-in-card' THEN 'SIGN_STREAK_RESTORE'
        WHEN 'electric-baton' THEN 'ROB_BOOST'
        WHEN 'red-bull' THEN 'ROB_BOOST'
        WHEN 'fishing-luck-bait' THEN 'FISHING_LUCK'
        ELSE 'NONE'
    END,
    CASE grouped.mapped_code
        WHEN 'double-sign-card' THEN 2.00
        WHEN 'triple-sign-card' THEN 3.00
        WHEN 'sign-monthly-card' THEN 5.00
        ELSE 0.00
    END,
    NULL,
    0
FROM (
    SELECT
        CASE
            WHEN b.propCode = 'sign-2' THEN 'double-sign-card'
            WHEN b.propCode = 'sign-3' THEN 'triple-sign-card'
            WHEN b.propCode = 'sign-in' THEN 'sign-in-card'
            WHEN b.propCode = 'sign-monthly' THEN 'sign-monthly-card'
            WHEN b.propCode = 'baton' THEN 'electric-baton'
            WHEN b.propCode = 'red-eyes' THEN 'red-bull'
            WHEN b.propCode = 'health' THEN 'health-card'
            WHEN b.propCode = 'rename' THEN 'rename-card'
            WHEN b.propCode LIKE 'bait%' THEN 'fishing-luck-bait'
            ELSE b.propCode
        END AS mapped_code,
        COALESCE(
            MAX(JSON_UNQUOTE(JSON_EXTRACT(p.data, '$.name'))),
            b.propCode
        ) AS item_name,
        COALESCE(
            MAX(JSON_UNQUOTE(JSON_EXTRACT(p.data, '$.description'))),
            'Migrated HuYan item'
        ) AS item_description,
        COALESCE(
            MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(p.data, '$.cost')) AS DECIMAL(19,2))),
            0.00
        ) AS item_price
    FROM hy_economy.userbackpack b
    LEFT JOIN hy_economy.propsdata p ON p.id = b.propId
    WHERE b.propCode IS NOT NULL AND b.propCode <> ''
    GROUP BY b.propCode
) grouped
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    price = VALUES(price);

-- Remove old-code stacks before re-importing with 1.1.1 item codes.
DELETE FROM dmw.economy_user_item
WHERE item_code IN (
    'sign-2', 'sign-3', 'sign-in', 'sign-monthly', 'baton',
    'red-eyes', 'health', 'rename',
    'bait-1', 'bait-2', 'bait-3', 'bait-l-1', 'bait-q-1'
);

-- Migrated backpack stacks.
INSERT INTO dmw.economy_user_item
    (user_id, item_code, quantity, updated_at)
SELECT
    CAST(SUBSTRING(b.userId, 2) AS UNSIGNED),
    CASE
        WHEN b.propCode = 'sign-2' THEN 'double-sign-card'
        WHEN b.propCode = 'sign-3' THEN 'triple-sign-card'
        WHEN b.propCode = 'sign-in' THEN 'sign-in-card'
        WHEN b.propCode = 'sign-monthly' THEN 'sign-monthly-card'
        WHEN b.propCode = 'baton' THEN 'electric-baton'
        WHEN b.propCode = 'red-eyes' THEN 'red-bull'
        WHEN b.propCode = 'health' THEN 'health-card'
        WHEN b.propCode = 'rename' THEN 'rename-card'
        WHEN b.propCode LIKE 'bait%' THEN 'fishing-luck-bait'
        ELSE b.propCode
    END AS mapped_code,
    SUM(GREATEST(
        1,
        COALESCE(
            CAST(JSON_UNQUOTE(JSON_EXTRACT(p.data, '$.num')) AS UNSIGNED),
            1
        )
    )),
    NOW()
FROM hy_economy.userbackpack b
LEFT JOIN hy_economy.propsdata p ON p.id = b.propId
WHERE b.userId REGEXP '^u[0-9]+$'
  AND b.propCode IS NOT NULL
  AND b.propCode <> ''
GROUP BY
    CAST(SUBSTRING(b.userId, 2) AS UNSIGNED),
    CASE
        WHEN b.propCode = 'sign-2' THEN 'double-sign-card'
        WHEN b.propCode = 'sign-3' THEN 'triple-sign-card'
        WHEN b.propCode = 'sign-in' THEN 'sign-in-card'
        WHEN b.propCode = 'sign-monthly' THEN 'sign-monthly-card'
        WHEN b.propCode = 'baton' THEN 'electric-baton'
        WHEN b.propCode = 'red-eyes' THEN 'red-bull'
        WHEN b.propCode = 'health' THEN 'health-card'
        WHEN b.propCode = 'rename' THEN 'rename-card'
        WHEN b.propCode LIKE 'bait%' THEN 'fishing-luck-bait'
        ELSE b.propCode
    END
ON DUPLICATE KEY UPDATE
    quantity = VALUES(quantity),
    updated_at = NOW();

-- Migrated title definitions and ownership.
INSERT INTO dmw.economy_title_definition
    (code, name, description, price, enabled)
SELECT
    t.code,
    MAX(COALESCE(NULLIF(t.name, ''), t.code)),
    'Migrated from HuYanEconomy',
    0,
    0
FROM hy_economy.titleinfo t
WHERE t.code IS NOT NULL AND t.code <> ''
GROUP BY t.code
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

INSERT IGNORE INTO dmw.economy_user_title
    (user_id, title_code, acquired_at)
SELECT
    t.userId,
    t.code,
    COALESCE(t.dueTime, NOW())
FROM hy_economy.titleinfo t
WHERE t.userId IS NOT NULL
  AND t.code IS NOT NULL
  AND t.code <> '';

-- Fishing history. New fish definitions are seeded by 1.1.0, so only catches are migrated.
INSERT INTO dmw.economy_fishing_catch
    (user_id, fish_id, fish_name, size_cm, reward, caught_at)
SELECT
    r.qq,
    COALESCE(r.FishId, 0),
    COALESCE(f.name, '未知鱼'),
    GREATEST(COALESCE(r.dimensions, 0), 0),
    ROUND(COALESCE(r.money, 0), 2),
    COALESCE(r.date, NOW())
FROM hy_economy.fishranking r
LEFT JOIN hy_economy.fish f ON f.id = r.FishId
WHERE r.qq IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM dmw.economy_fishing_catch c
      WHERE c.user_id = r.qq
        AND c.fish_id = COALESCE(r.FishId, 0)
        AND c.size_cm = GREATEST(COALESCE(r.dimensions, 0), 0)
        AND c.caught_at = COALESCE(r.date, NOW())
  );

INSERT INTO dmw.economy_migration_log
    (step_name, source_rows, target_rows, executed_at)
SELECT
    @migration_name,
    (
        SELECT COUNT(*)
        FROM economy.economy_balance_record
        WHERE context = 'cn.chahuyun.HuYanEconomy'
          AND currency = 'hy-gold'
          AND uuid REGEXP '^u[0-9]+$'
    )
    + (
        SELECT COUNT(*)
        FROM economy.economy_balance_record
        WHERE context = 'global-economy'
          AND currency = 'hy-gold'
          AND uuid REGEXP '^u[0-9]+$'
    )
    + (SELECT COUNT(*) FROM hy_economy.userinfo)
    + (SELECT COUNT(*) FROM hy_economy.userbackpack)
    + (SELECT COUNT(*) FROM hy_economy.titleinfo)
    + (SELECT COUNT(*) FROM hy_economy.fishranking),
    (SELECT COUNT(*) FROM dmw.economy_account)
    + (SELECT COUNT(*) FROM dmw.economy_user_profile)
    + (SELECT COUNT(*) FROM dmw.economy_user_item)
    + (SELECT COUNT(*) FROM dmw.economy_user_title)
    + (SELECT COUNT(*) FROM dmw.economy_fishing_catch),
    NOW()
ON DUPLICATE KEY UPDATE
    source_rows = VALUES(source_rows),
    target_rows = VALUES(target_rows),
    executed_at = NOW();

COMMIT;

SELECT 'economy_account' AS target, COUNT(*) AS rows_migrated FROM dmw.economy_account
UNION ALL SELECT 'economy_user_profile', COUNT(*) FROM dmw.economy_user_profile
UNION ALL SELECT 'economy_item_definition', COUNT(*) FROM dmw.economy_item_definition
UNION ALL SELECT 'economy_user_item', COUNT(*) FROM dmw.economy_user_item
UNION ALL SELECT 'economy_title_definition', COUNT(*) FROM dmw.economy_title_definition
UNION ALL SELECT 'economy_user_title', COUNT(*) FROM dmw.economy_user_title
UNION ALL SELECT 'economy_fishing_catch', COUNT(*) FROM dmw.economy_fishing_catch
UNION ALL SELECT 'economy_main_bank', COUNT(*) FROM dmw.economy_account WHERE scope = 'MAIN_BANK';
