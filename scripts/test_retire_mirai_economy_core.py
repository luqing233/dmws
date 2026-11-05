import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

import retire_mirai_economy_core as retirement


class DependencyScanTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.base = Path(self.directory.name)
        (self.base / "plugins").mkdir()
        self.core = self.base / "plugins/core.jar"
        self.jar(self.core, "Core.class", b"mirai-economy-core")

    def tearDown(self):
        self.directory.cleanup()

    def jar(self, path, member, content):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(member, content)

    def scan(self):
        with patch.object(retirement, "BASE", self.base), patch.object(retirement, "CORE", self.core):
            return retirement.dependency_scan()

    def test_safe_plugins_pass_and_core_does_not_depend_on_itself(self):
        self.jar(self.base / "plugins/safe.jar", "Safe.class", b"some/other/Plugin")
        self.assertEqual({"safe.jar": []}, self.scan())

    def test_bytecode_reference_prevents_retirement(self):
        self.jar(self.base / "plugins/dependent.jar", "Dependent.class",
                 b"xyz/cssxsh/mirai/economy/service/EconomyService")
        with self.assertRaises(RuntimeError):
            self.scan()

    def test_declared_optional_dependency_also_prevents_retirement(self):
        self.jar(self.base / "plugins/dependent.jar", "plugin.yml",
                 b"dependencies: [mirai-economy-core]")
        with self.assertRaises(RuntimeError):
            self.scan()

    def test_old_process_must_exit_before_api_validation(self):
        proc = self.base / "proc"
        (proc / "10").mkdir(parents=True)
        (proc / "20").mkdir()
        (proc / "20/comm").write_text("java\n")
        self.assertFalse(retirement.replacement_is_running(10, 20, proc))
        (proc / "10").rmdir()
        self.assertTrue(retirement.replacement_is_running(10, 20, proc))

    def test_launcher_is_not_mistaken_for_replacement_jvm(self):
        proc = self.base / "proc"
        (proc / "20").mkdir(parents=True)
        (proc / "20/comm").write_text("python3\n")
        self.assertFalse(retirement.replacement_is_running(10, 20, proc))


if __name__ == "__main__":
    unittest.main()
