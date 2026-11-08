import unittest
from deploy_fishing_adventure import migrate


class FakeDatabase:
    def __init__(self):
        self.statements = []
        self.reason = "enum('FISHING_COST','FISHING_REWARD')"
        self.columns = set()

    def sql(self, statement, database="dmw"):
        self.statements.append((statement, database))
        if "SELECT COUNT(*)" in statement:
            return "1" if statement in self.columns else "0"
        if "SELECT COLUMN_TYPE" in statement:
            return self.reason
        if "ADD COLUMN" in statement:
            table = statement.split('`')[1]
            column = statement.split('`')[3]
            self.columns.add("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + database
                             + "' AND TABLE_NAME='" + table + "' AND COLUMN_NAME='" + column + "'")
        if "MODIFY reason" in statement:
            self.reason = statement.split("MODIFY reason ")[1].removesuffix(" NOT NULL")
        return ""


class FishingDeploymentTests(unittest.TestCase):
    def test_rejects_arbitrary_databases_before_any_statement(self):
        db = FakeDatabase()
        for name in ["hy_economy", "economy", "dmw;DROP DATABASE dmw", "dmw_fishing_restore_x"]:
            with self.assertRaises(ValueError):
                migrate(db, name)
        self.assertEqual([], db.statements)

    def test_migration_adds_columns_and_enums_without_rewriting_historical_fields(self):
        db = FakeDatabase()
        migrate(db, "dmw_fishing_restore_20261108210000")
        count = len([s for s, _ in db.statements if "ADD COLUMN" in s])
        self.assertEqual(4, count)
        self.assertEqual(1, db.reason.count("'FISHING_PENALTY'"))
        migrate(db, "dmw_fishing_restore_20261108210000")
        self.assertEqual(count, len([s for s, _ in db.statements if "ADD COLUMN" in s]))
        self.assertEqual(1, db.reason.count("'FISHING_EVENT_REWARD'"))
        updates = [s for s, _ in db.statements if s.startswith("UPDATE")]
        self.assertTrue(all("WHERE crime>0 AND crime_decay_at IS NULL" in s for s in updates))
        self.assertFalse(any("DROP " in s or "DELETE " in s or "TRUNCATE " in s for s, _ in db.statements))


if __name__ == "__main__":
    unittest.main()
