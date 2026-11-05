import copy
import unittest
from repair_confirmed_chat_incidents import validate, ORIGINAL, DUPLICATE, REFUND


class ConfirmedIncidentTests(unittest.TestCase):
    def snapshot(self):
        return {"drift": [{"count": 0}], "operations": [],
                "inventory": [{"quantity": 42}],
                "condition": [{"crime": 20, "injury": 84, "session": "c5f3b2e1-4008-429b-9d6d-f4a1323655ef"}],
                "ledger": [dict(op=op, key="u1329158513", scope="WALLET", delta=-300,
                                reason="SHOP_PURCHASE", description="购买 幸运鱼饵 x1") for op in (ORIGINAL, DUPLICATE)]}

    def test_only_exact_purchase_and_unsettled_stay_are_eligible(self):
        value = self.snapshot()
        self.assertEqual(validate(value), (False, False))
        for section, field, wrong in [("ledger", "delta", -600), ("ledger", "key", "u123"),
                                      ("inventory", "quantity", 0), ("condition", "crime", 25),
                                      ("condition", "session", "another-stay"), ("drift", "count", 1)]:
            changed = copy.deepcopy(value); changed[section][0][field] = wrong
            with self.assertRaises(RuntimeError): validate(changed)

    def test_does_not_treat_a_marker_without_its_refund_as_completed(self):
        value = self.snapshot(); value["operations"] = [{"id": REFUND}]
        with self.assertRaises(RuntimeError): validate(value)
        value["ledger"].append(dict(op=REFUND, key="u1329158513", scope="WALLET", delta=300))
        self.assertEqual(validate(value), (True, False))


if __name__ == "__main__": unittest.main()
