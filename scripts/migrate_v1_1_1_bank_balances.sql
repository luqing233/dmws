-- DMWS main-bank balance repair for 1.1.1.
-- Source context: economy.economy_balance_record / global-economy / hy-gold
-- Target scope: dmw.economy_account / MAIN_BANK
-- Safe to rerun: existing target accounts are never overwritten.

SET NAMES utf8mb4;
SET @migration_name = 'dmws-main-bank-migration-1.1.1';

START TRANSACTION;

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

INSERT INTO dmw.economy_migration_log
    (step_name, source_rows, target_rows, executed_at)
SELECT
    @migration_name,
    (
        SELECT COUNT(*)
        FROM economy.economy_balance_record
        WHERE context = 'global-economy'
          AND currency = 'hy-gold'
          AND uuid REGEXP '^u[0-9]+$'
    ),
    (
        SELECT COUNT(*)
        FROM dmw.economy_account
        WHERE scope = 'MAIN_BANK'
    ),
    NOW()
ON DUPLICATE KEY UPDATE
    source_rows = VALUES(source_rows),
    target_rows = VALUES(target_rows),
    executed_at = NOW();

COMMIT;

SELECT 'source_main_bank_accounts' AS metric, COUNT(*) AS value
FROM economy.economy_balance_record
WHERE context = 'global-economy'
  AND currency = 'hy-gold'
  AND uuid REGEXP '^u[0-9]+$'
UNION ALL
SELECT 'source_main_bank_total', ROUND(SUM(balance), 2)
FROM economy.economy_balance_record
WHERE context = 'global-economy'
  AND currency = 'hy-gold'
  AND uuid REGEXP '^u[0-9]+$'
UNION ALL
SELECT 'target_main_bank_accounts', COUNT(*)
FROM dmw.economy_account
WHERE scope = 'MAIN_BANK'
UNION ALL
SELECT 'target_main_bank_total', ROUND(SUM(balance), 2)
FROM dmw.economy_account
WHERE scope = 'MAIN_BANK'
UNION ALL
SELECT 'missing_or_mismatched', COUNT(*)
FROM economy.economy_balance_record src
LEFT JOIN dmw.economy_account target
    ON target.account_key = src.uuid
   AND target.scope = 'MAIN_BANK'
WHERE src.context = 'global-economy'
  AND src.currency = 'hy-gold'
  AND src.uuid REGEXP '^u[0-9]+$'
  AND (
      target.id IS NULL
      OR ABS(target.balance - GREATEST(ROUND(src.balance, 2), 0.00)) >= 0.001
  );
