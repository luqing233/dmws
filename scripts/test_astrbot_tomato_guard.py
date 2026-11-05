import json
import unittest
from types import SimpleNamespace
from astrbot_tomato_guard.guard import start, finish, receipt, next_receipt


class Event:
    def __init__(self): self.values = {}
    def get_extra(self, k, default=None): return self.values.get(k, default)
    def set_extra(self, k, v): self.values[k] = v
    def get_sender_id(self): return "7"


class TomatoReceiptTests(unittest.TestCase):
    def result(self, payload, error=False):
        return SimpleNamespace(isError=error, content=[SimpleNamespace(text=json.dumps(payload))])

    def test_partial_download_error_is_preserved_even_without_protocol_error(self):
        e = Event(); args = {"book_id": "123", "item_ids": ["901"], "download_missing": True}
        start(e, "tnd_get_chapters", args)
        finish(e, "tnd_get_chapters", args, self.result({"status": "partial", "download_error": {"code": "BATCH_BUSY", "message": "批次忙"}}))
        self.assertIn("批次忙", receipt(e)); self.assertIn("不代表下载完成", receipt(e))

    def test_canceling_and_cancel_signal_failure_never_say_canceled(self):
        e = Event(); args = {"batch_id": "old"}
        start(e, "dmw_cancel_download_batch", args, [7])
        finish(e, "dmw_cancel_download_batch", args, self.result({"status": "canceling", "cancel_error": "denied"}))
        self.assertIn("尚未确认已停止", receipt(e)); self.assertNotIn("批次已取消", receipt(e))

    def test_retry_uses_new_id_and_unknown_timeout_stays_unknown(self):
        e = Event(); args = {"batch_id": "old"}
        start(e, "tnd_retry_batch", args, [7]); self.assertIn("尚未确认", receipt(e))
        finish(e, "tnd_retry_batch", args, self.result({"status": "running", "batch_id": "new", "retry_of": "old"}))
        self.assertNotIn("new", receipt(e)); self.assertIn("尚未完成", receipt(e))
        self.assertIn("新", receipt(e)); self.assertIn("batch:new", e.get_extra("dmw_tomato_receipts_v1"))

    def test_queued_job_can_only_be_reported_complete_after_real_terminal_result(self):
        e = Event(); args = {"book_id": "123", "operator": 999, "confirm": True}
        start(e, "dmw_update_book", args, [7]); self.assertEqual(args["operator"], 7)
        finish(e, "dmw_update_book", args, self.result({"job_id": "job-a", "status": "queued", "done": False}))
        self.assertIn("尚未完成", receipt(e))
        finish(e, "dmw_get_update_job", {"job_id": "job-a"}, self.result({"job_id": "job-a", "status": "completed", "done": True, "success": 2, "exist": 3, "fail": 0}))
        self.assertNotIn("尚未完成", receipt(e)); self.assertIn("新增 2", receipt(e))

    def test_subscription_registration_does_not_mean_data_backfill_finished(self):
        e = Event()
        finish(e, "dmw_get_update_job", {"job_id": "sub-a"}, self.result({"job_id": "sub-a", "status": "completed", "done": True, "subscription_id": 8, "data_job_id": "data-a"}))
        self.assertIn("订阅已登记", receipt(e)); self.assertIn("后台补全", receipt(e)); self.assertNotIn("data-a", receipt(e))

    def test_long_internal_job_id_stays_in_state_but_never_in_human_receipt(self):
        job = "sub-2f55ddf5-d437-4650-980d-4bffa8afa23e"
        e = Event()
        finish(e, "dmw_subscribe_book", {"book_id": "123"}, self.result({"job_id": job, "status": "queued", "done": False}))
        self.assertIn("尚未完成", receipt(e)); self.assertNotIn(job, receipt(e)); self.assertNotIn("任务：", receipt(e))
        self.assertIn("job:" + job, e.get_extra("dmw_tomato_receipts_v1"))
        finish(e, "dmw_get_update_job", {"job_id": job}, self.result({"job_id": job, "status": "completed", "done": True,
               "subscription_id": 9, "book_name": "一魂双体", "data_job_id": "1dc84ad8-8f5f-45d5-87b5-c65421ae2241"}))
        self.assertIn("《一魂双体》订阅已登记", receipt(e)); self.assertNotIn("1dc84", receipt(e))

    def test_repeated_progress_is_suppressed_but_completed_result_is_delivered(self):
        e = Event(); args = {"job_id": "sub-a"}
        finish(e, "dmw_get_update_job", args, self.result({"job_id": "sub-a", "status": "queued", "done": False}))
        self.assertTrue(next_receipt(e)); self.assertEqual("", next_receipt(e))
        finish(e, "dmw_get_update_job", args, self.result({"job_id": "sub-a", "status": "running", "done": False}))
        self.assertEqual("", next_receipt(e))
        finish(e, "dmw_get_update_job", args, self.result({"job_id": "sub-a", "status": "completed", "done": True, "subscription_id": 9}))
        self.assertIn("订阅已登记", next_receipt(e)); self.assertEqual("", next_receipt(e))

    def test_completion_marker_without_done_cannot_claim_success(self):
        e = Event()
        finish(e, "dmw_get_update_job", {"job_id": "sub-a"}, self.result({"job_id": "sub-a", "status": "completed", "done": False, "subscription_id": 9}))
        self.assertIn("尚未完成", receipt(e)); self.assertNotIn("订阅已登记", receipt(e))

    def test_upstream_display_text_and_errors_do_not_leak_uuid(self):
        e = Event(); ident = "1dc84ad8-8f5f-45d5-87b5-c65421ae2241"
        finish(e, "dmw_get_download_batch", {"batch_id": ident}, self.result({"batch_id": ident, "status": "running",
               "display_text": "下载批次：" + ident + "\n仍在处理，尚未完成。"}))
        self.assertNotIn(ident, receipt(e)); self.assertIn("尚未完成", receipt(e))

    def test_unsubscribe_uses_the_real_success_message(self):
        e = Event(); args = {"book_id": "123", "operator": 7, "confirm": True}
        start(e, "dmw_unsubscribe_book", args, [7])
        finish(e, "dmw_unsubscribe_book", args, self.result({"ok": True, "message": "已取消订阅"}))
        self.assertEqual(receipt(e), "已取消订阅")

    def test_model_cannot_impersonate_a_master_for_batch_controls(self):
        e = Event(); args = {"batch_id": "old", "operator": 999, "confirm": True}
        start(e, "dmw_cancel_download_batch", args, [7])
        self.assertEqual(args["operator"], 7)
        e = Event(); args = {"batch_id": "old", "operator": 999, "confirm": True}
        start(e, "dmw_cancel_download_batch", args, [999])
        self.assertEqual(args, {}); self.assertIn("仅限主人", receipt(e))

    def test_direct_downloader_cancel_is_blocked_for_non_master_even_if_model_claims_success(self):
        e = Event(); args = {"batch_id": "old"}
        start(e, "tnd_cancel_batch", args, [999])
        self.assertEqual(args, {})
        finish(e, "tnd_cancel_batch", args, self.result({"status": "canceled"}))
        self.assertIn("本次未执行", receipt(e)); self.assertNotIn("批次已取消", receipt(e))

    def test_diagnostic_false_is_a_report_and_plain_protocol_errors_stay_errors(self):
        e = Event()
        finish(e, "tnd_get_diagnostics", {}, self.result({"ok": False, "worker": {"status": "stale"}}))
        self.assertIn("并非HTTP调用失败", receipt(e)); self.assertIn("stale", receipt(e))
        e = Event()
        result = SimpleNamespace(isError=True, content=[SimpleNamespace(text="参数无效")])
        finish(e, "dmw_get_chapters", {}, result)
        self.assertEqual(receipt(e), "小说操作未完成：参数无效")

    def test_economy_tools_and_ready_body_are_not_rewritten(self):
        e = Event(); start(e, "dmw_economy_buy", {}); finish(e, "dmw_economy_buy", {}, None)
        self.assertIsNone(receipt(e))
        finish(e, "dmw_get_chapters", {"book_id": "123"}, self.result({"status": "ready", "complete": True}))
        self.assertIsNone(receipt(e))


if __name__ == "__main__": unittest.main()
