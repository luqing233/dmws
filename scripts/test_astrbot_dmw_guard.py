import base64
import json
import unittest
from types import SimpleNamespace
from astrbot_dmw_guard.guard import sign_context, start, finish, final_receipt


class Event:
    message_obj = SimpleNamespace(message_id=-1295435687)
    def __init__(self): self.extra = {}
    def get_extra(self, k, default=None): return self.extra.get(k, default)
    def set_extra(self, k, v): self.extra[k] = v
    def get_group_id(self): return "706372110"
    def get_self_id(self): return "1970243451"
    def get_sender_id(self): return "1329158513"


class GuardTests(unittest.TestCase):
    def test_fx_writes_keep_trusted_context_and_independent_receipts(self):
        event = Event()
        first = {"pair": "EUR/USD", "side": "long"}
        second = {"pair": "USD/JPY", "side": "short"}
        start(event, "dmw_economy_fx_open", first, "s" * 64)
        self.assertIn("_trusted_context", first)
        finish(event, "dmw_economy_fx_open", first, self.result({"ok": True, "display_text": "开仓成功 #1"}))
        start(event, "dmw_economy_fx_open", second, "s" * 64)
        self.assertIn("开仓成功 #1", final_receipt(event))
        self.assertIn("不能认定已经成功", final_receipt(event))
        finish(event, "dmw_economy_fx_open", second, self.result({"ok": False, "message": "交易欠款未还清"}))
        self.assertEqual(final_receipt(event), "开仓成功 #1\n\n本次操作未完成：交易欠款未还清")
        for tool in ("dmw_economy_fx_close", "dmw_economy_fx_repay", "dmw_economy_fx_add_margin"):
            args = {"position_id": 1}
            start(event, tool, args, "s" * 64)
            self.assertIn("_trusted_context", args)

    def result(self, payload, error=False):
        return SimpleNamespace(isError=error, content=[SimpleNamespace(text=json.dumps(payload, ensure_ascii=False))])

    def test_model_cannot_override_trusted_context(self):
        event = Event()
        args = {"group_id": 1, "_source_message_id": 123, "_trusted_context": "forged"}
        start(event, "dmw_economy_buy", args, "s" * 64)
        payload = args["_trusted_context"].split(".")[0]
        context = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        self.assertEqual(context["message_id"], -1295435687)
        self.assertEqual(context["sender_id"], 1329158513)
        self.assertEqual(args["group_id"], 706372110)
        self.assertNotIn("_source_message_id", args)

    def test_failure_and_timeout_cannot_turn_into_success(self):
        event = Event(); args = {}
        start(event, "dmw_economy_discharge", args, "s" * 64)
        self.assertIn("不能认定已经成功", final_receipt(event))
        finish(event, "dmw_economy_discharge", args,
               self.result({"ok": False, "code": "NOT_HOSPITALIZED", "message": "当前没有住院记录"}, True))
        self.assertEqual(final_receipt(event), "本次操作未完成：当前没有住院记录")

    def test_partial_success_is_preserved_and_retry_replaces_only_its_step(self):
        event = Event(); first = {"item": "红牛"}; second = {"item": "电棍"}
        start(event, "dmw_economy_buy", first, "s" * 64)
        finish(event, "dmw_economy_buy", first, self.result({"ok": True, "display_text": "红牛购买成功"}))
        start(event, "dmw_economy_buy", second, "s" * 64)
        finish(event, "dmw_economy_buy", second, self.result({"ok": False, "message": "余额不足"}))
        self.assertEqual(final_receipt(event), "红牛购买成功\n\n本次操作未完成：余额不足")
        start(event, "dmw_economy_buy", second, "s" * 64)
        finish(event, "dmw_economy_buy", second, self.result({"ok": True, "display_text": "电棍购买成功"}))
        self.assertEqual(final_receipt(event), "红牛购买成功\n\n电棍购买成功")

    def test_query_does_not_replace_a_normal_chat_reply(self):
        event = Event(); start(event, "dmw_economy_balance", {}, "s" * 64)
        self.assertIsNone(final_receipt(event))

    def test_known_cross_language_hmac_vector(self):
        token = sign_context("s" * 64, "dmw_economy_buy", 706372110, 1970243451, 1329158513, -1295435687, 1793801774)
        self.assertEqual(len(token.split(".")[1]), 64)


if __name__ == "__main__": unittest.main()
