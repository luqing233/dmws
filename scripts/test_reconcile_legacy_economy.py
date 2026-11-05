import unittest
from unittest.mock import Mock, patch

from reconcile_legacy_economy import adjustment, corrected_quantity, imported_quantity, identifier, mapped_code, finish_recovery_placeholder


class ReconciliationTests(unittest.TestCase):
    def test_extracted_column_supersedes_stale_json(self):
        prop = dict(prop_id=1, column_code="bait-l-1", column_num=1, json_num=159)
        self.assertEqual(159, imported_quantity(prop))
        self.assertEqual(1, corrected_quantity(prop))

    def test_depleted_legacy_stack_is_not_resurrected(self):
        prop = dict(prop_id=1, column_code="", column_num=0, json_num=0)
        self.assertEqual(1, imported_quantity(prop))
        self.assertEqual(0, corrected_quantity(prop))

    def test_current_purchases_and_consumption_are_preserved(self):
        self.assertEqual((5, 0), adjustment(current=8, imported=4, corrected=1))
        self.assertEqual((0, 1), adjustment(current=2, imported=4, corrected=1))

    def test_orphan_preserves_existing_import_not_invented_quantity(self):
        self.assertEqual(1, corrected_quantity(dict(prop_id=None, json_num=None)))

    def test_codes_merge_only_known_aliases(self):
        self.assertEqual("fishing-luck-bait", mapped_code("bait-q-1"))
        self.assertEqual("double-sign-card", mapped_code("sign-2"))
        self.assertEqual("custom-item", mapped_code("custom-item"))

    def test_identifiers_reject_sql_injection(self):
        self.assertEqual("`hy_economy`", identifier("hy_economy"))
        with self.assertRaises(ValueError):
            identifier("hy_economy`;DROP TABLE fish")

    def test_archived_nonempty_recovery_note_can_be_retired(self):
        db = Mock()
        db.tables.return_value = ["recover_your_data_info"]
        db.sql.side_effect = ["READ_ME", "0", ""]
        manifest = [dict(schema="economy", table="recover_your_data_info", rows=1, hash="verified")]
        with patch("reconcile_legacy_economy.fingerprint", return_value=("verified", 1)):
            finish_recovery_placeholder(db, manifest)
        self.assertEqual("DROP TABLE economy.recover_your_data_info", db.sql.call_args.args[0])

    def test_unexpected_recovery_business_columns_block_drop(self):
        db = Mock()
        db.tables.return_value = ["recover_your_data_info"]
        db.sql.return_value = "READ_ME\nbalance"
        with self.assertRaises(RuntimeError):
            finish_recovery_placeholder(db, [])
        self.assertFalse(any("DROP TABLE" in call.args[0] for call in db.sql.call_args_list))

    def test_changed_recovery_note_blocks_drop(self):
        db = Mock()
        db.tables.return_value = ["recover_your_data_info"]
        db.sql.return_value = "READ_ME"
        manifest = [dict(schema="economy", table="recover_your_data_info", rows=1, hash="verified")]
        with patch("reconcile_legacy_economy.fingerprint", return_value=("modified", 1)):
            with self.assertRaises(RuntimeError):
                finish_recovery_placeholder(db, manifest)
        self.assertFalse(any("DROP TABLE" in call.args[0] for call in db.sql.call_args_list))


if __name__ == "__main__":
    unittest.main()
