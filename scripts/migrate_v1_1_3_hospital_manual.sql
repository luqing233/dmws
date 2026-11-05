-- Run with dmw selected, after stopping the old application and backing up active stays.
-- Only the forced-discharge deadline changes; injury/rates/session/fees/balances stay intact.
START TRANSACTION;
UPDATE economy_player_condition
   SET hospital_until = DATE_ADD(hospital_since, INTERVAL 24 HOUR)
 WHERE hospital_since IS NOT NULL
   AND hospital_until IS NOT NULL
   AND hospital_until <> DATE_ADD(hospital_since, INTERVAL 24 HOUR);
SET @dmw_hospital_changed = ROW_COUNT();
INSERT IGNORE INTO economy_operation(operation_id, operation_type, actor_id, description, created_at)
SELECT 'hospital-manual-24h:2026-11-04', 'HOSPITAL_POLICY_UPDATE', NULL,
       CONCAT('住院统一为本人出院、满24小时强制出院，调整活跃记录 ', @dmw_hospital_changed, ' 条；不变更费率或金币'), NOW()
 WHERE @dmw_hospital_changed > 0;
COMMIT;
