-- Select the bot's economy database first. Apply migrations and start the new version once before this file.
-- Optional examples: executing this file ADDS three enabled events. Repeating it preserves existing edits.
START TRANSACTION;
INSERT INTO economy_fishing_event
    (code, name, description, kind, pool_code, tickets, amount_min, amount_max, amount_step, enabled)
SELECT 'river-purse', '河底钱袋', '钱袋里有些零钱，也可能需要赔偿失主。', 'MONEY', 'FUN', 1, -20, 80, 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_event WHERE code='river-purse');

INSERT INTO economy_fishing_event
    (code, name, description, kind, pool_code, tickets, amount_min, amount_max, amount_step,
     strength_min, strength_max, win_injury_percent, light_percent, medium_percent, enabled)
SELECT 'river-crab', '巨型河蟹', '它举起了两只大钳子。', 'COMBAT', 'COMBAT', 1, 100, 300, 1,
       40, 70, 10, 60, 30, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_event WHERE code='river-crab');

INSERT INTO economy_fishing_event
    (code, name, description, kind, pool_code, tickets, amount_min, amount_max, amount_step, jail_minutes, enabled)
SELECT 'river-evidence', '可疑物证', '你似乎发现了案件的线索。', 'JAIL', 'NEGATIVE', 1, 0, 0, 1, 5, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_event WHERE code='river-evidence');

INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.encounter.1', 'river-purse', 'ENCOUNTER', '{player}捞起了{event}。', 3, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.encounter.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.encounter.2', 'river-purse', 'ENCOUNTER', '鱼没上钩，{event}倒是上来了。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.encounter.2');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.description.1', 'river-purse', 'DESCRIPTION', '钱袋里有些零钱，也可能需要赔偿失主。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.description.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.gain.1', 'river-purse', 'MONEY_GAIN', '你清点了钱袋，获得{reward}金币。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.gain.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.loss.1', 'river-purse', 'MONEY_LOSS', '失主找来了，你赔了{penalty}金币。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.loss.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-purse.zero.1', 'river-purse', 'MONEY_ZERO', '这是一个空钱袋。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-purse.zero.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-crab.win.1', 'river-crab', 'COMBAT_WIN', '你制服了{event}，获得{reward}金币。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-crab.win.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-crab.loss.1', 'river-crab', 'COMBAT_LOSS', '{event}挥着钳子把你赶跑了。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-crab.loss.1');
INSERT INTO economy_fishing_text (code, owner_code, scene, template, weight, enabled)
SELECT 'river-evidence.jail.1', 'river-evidence', 'JAIL', '你被带回警局配合调查{minutes}分钟。', 1, TRUE
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM economy_fishing_text WHERE code='river-evidence.jail.1');
COMMIT;
