import base64
import hashlib
import unittest
from hash_fishing_web_password import encode


class FishingWebPasswordTests(unittest.TestCase):
    def test_fixture_matches_java_verifier(self):
        encoded = encode("fish-test-password", bytes(range(16)))
        self.assertEqual("pbkdf2-sha256$310000$AAECAwQFBgcICQoLDA0ODw$f92pJW53GA3iaug9jH_lQ5bt_2TQOfl3FU9a37PGVYg", encoded)

    def test_random_salts_and_password_constraints(self):
        self.assertNotEqual(encode("fish-test-password"), encode("fish-test-password"))
        for password in ("", "too-short", "x" * 257):
            with self.assertRaises(ValueError):
                encode(password)
        with self.assertRaises(ValueError):
            encode("fish-test-password", iterations=9999999)


if __name__ == "__main__":
    unittest.main()
