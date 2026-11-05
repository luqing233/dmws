-- Reusable electric batons and golden-basin daily quota.
-- Stop economy writers and back up/restore-verify first. Apply only after checking
-- the new table and BOTH new columns are absent. This migration intentionally
-- fails on reapplication; it must never reset existing charge or daily usage.
-- Existing item rows, inventory, balances, crime and queued boosts are preserved.
ALTER TABLE economy_item_definition MODIFY COLUMN effect_type enum('BATON_CHARGER','CRIME_REDUCTION','ELECTRIC_BATON','FISHING_LUCK','MONEY','NONE','ROB_BOOST','SIGN_BONUS','SIGN_STREAK_RESTORE','TITLE_UNLOCK') NOT NULL;
ALTER TABLE economy_player_condition ADD COLUMN crime_relief_on DATE NULL, ADD COLUMN crime_relief_used INT NOT NULL DEFAULT 0;
CREATE TABLE economy_electric_baton (id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, energy INT NOT NULL, charging_since DATETIME(6) NULL, PRIMARY KEY(id), KEY idx_baton_owner(user_id));
