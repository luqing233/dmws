from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

import yaml

from update_server_config import clean_config, update_file


class ConfigCleanupTests(unittest.TestCase):
    def test_removes_only_retired_keys_and_preserves_live_connections(self):
        config = {"spring": {"datasource": {"dmw": {"password": "secret"}, "economy": {}, "hy_economy": {}}},
                  "shiro": {"ws": {"client": {"url": "ws://onebot:3001"}}},
                  "dmws": {"ws-url": "unused", "httpUrl": "unused", "max-messages": 40,
                           "bot-id": 123, "mcp": {"downloader": {"token": "preserve-token"},
                                                    "economy": {"operator-id": 456}}}}
        before = deepcopy(config)
        updated, removed = clean_config(config)
        self.assertEqual(before, config)
        self.assertEqual({"dmw": {"password": "secret"}}, updated["spring"]["datasource"])
        self.assertEqual(config["shiro"], updated["shiro"])
        self.assertEqual(config["dmws"]["mcp"], updated["dmws"]["mcp"])
        self.assertEqual(5, len(removed))
        self.assertEqual((updated, []), clean_config(updated))

    def test_backup_preserves_original_bytes_and_second_run_is_noop(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "application.yaml"
            original = b"# preserve in backup\r\ndmws:\r\n  max-messages: 40\r\n  bot-id: 123\r\n"
            path.write_bytes(original)
            report = update_file(path, backup_dir=root / "backups")
            self.assertTrue(report["changed"])
            self.assertEqual(original, Path(report["backup"]).read_bytes())
            self.assertEqual({"dmws": {"bot-id": 123}}, yaml.safe_load(path.read_text()))
            self.assertFalse(update_file(path, backup_dir=root / "backups")["changed"])
            self.assertEqual(1, len(list((root / "backups").iterdir())))

    def test_dry_run_does_not_write_or_back_up(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "application.yaml"
            path.write_text("dmws:\n  max-messages: 40\n")
            before = path.read_bytes()
            report = update_file(path, dry_run=True, backup_dir=Path(directory) / "backups")
            self.assertTrue(report["changed"])
            self.assertEqual(before, path.read_bytes())
            self.assertFalse((Path(directory) / "backups").exists())

    def test_explicit_operator_override_and_invalid_id(self):
        self.assertEqual(123, clean_config({}, 123)[0]["dmws"]["mcp"]["economy"]["operator-id"])
        with self.assertRaises(ValueError):
            clean_config({}, 0)


if __name__ == "__main__":
    unittest.main()
