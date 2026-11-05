"""Novel-only receipts: preserve partial/pending/error outcomes instead of model success claims."""
import json
import re

KEY = "dmw_tomato_receipts_v1"
READS = {"tnd_get_chapters", "dmw_get_chapters", "dmw_get_chapter_content"}
JOBS = {"dmw_subscribe_book", "dmw_update_book", "dmw_get_update_job"}
AUTH_WRITES = {"dmw_subscribe_book", "dmw_update_book", "dmw_unsubscribe_book"}
DIAGNOSTICS = {"dmw_get_downloader_diagnostics", "tnd_get_diagnostics"}
BATCH = {"tnd_cancel_batch", "tnd_retry_batch", "tnd_get_batch_status", "dmw_cancel_download_batch",
         "dmw_retry_download_batch", "dmw_get_download_batch"}


def key(tool, args):
    if tool in DIAGNOSTICS:
        return "diagnostics"
    if tool in JOBS:
        return "job:" + str(args.get("job_id") or args.get("book_id", ""))
    if tool in BATCH:
        return "batch:" + str(args.get("batch_id", ""))
    return "read:" + json.dumps([args.get("book_id"), args.get("item_ids", args.get("chapter_id", args.get("order")))], sort_keys=True)


def start(event, tool, args, masters=()):
    write = tool in BATCH and ("cancel" in tool or "retry" in tool)
    write |= tool in READS and args.get("download_missing") is True
    write |= tool in AUTH_WRITES
    if (tool in BATCH and ("cancel" in tool or "retry" in tool)) or tool in AUTH_WRITES:
        sender = str(event.get_sender_id())
        if sender not in {str(value) for value in masters}:
            state = dict(event.get_extra(KEY, {}))
            state["policy:" + tool] = "这项小说管理操作仅限主人使用，本次未执行。"
            event.set_extra(KEY, state)
            blocked = set(event.get_extra(KEY + ":blocked", [])); blocked.add(tool)
            event.set_extra(KEY + ":blocked", list(blocked))
            args.clear()  # Required batch_id is absent: both MCP servers reject before any mutation.
            return
        if tool.startswith("dmw_"):
            args["operator"] = int(sender)  # Never accept a master ID invented by the model.
    if write:
        state = dict(event.get_extra(KEY, {}))
        state[key(tool, args)] = "小说操作结果尚未确认，不能认定下载、取消或重试已经完成。"
        event.set_extra(KEY, state)


def finish(event, tool, args, result):
    if tool not in READS | BATCH | DIAGNOSTICS | JOBS | AUTH_WRITES:
        return
    if tool in event.get_extra(KEY + ":blocked", []):
        return
    state = dict(event.get_extra(KEY, {}))
    identifier = key(tool, args)
    payload = None
    raw = []
    if result is not None:
        for block in getattr(result, "content", []) or []:
            raw.append(str(getattr(block, "text", "")))
            try:
                value = json.loads(getattr(block, "text", ""))
                if isinstance(value, dict): payload = value; break
            except (ValueError, TypeError): pass
    if tool in DIAGNOSTICS and payload is not None and not getattr(result, "isError", False):
        diagnostic = payload.get("diagnostics", payload)
        if diagnostic.get("ok") is False:
            state[identifier] = "下载器诊断发现异常（并非HTTP调用失败）：" + json.dumps(diagnostic, ensure_ascii=False)[:1800]
        else:
            state.pop(identifier, None)
    elif payload is None:
        state[identifier] = ("小说操作未完成：" + "；".join(raw)[:300]) if result is not None and getattr(result, "isError", False) else "小说工具未返回可核验结果，请查询后再试；不能认定操作成功。"
    elif getattr(result, "isError", False) or payload.get("ok") is False or payload.get("mcp_is_error"):
        state[identifier] = "小说操作未完成：" + str(payload.get("message") or payload.get("code") or "工具返回失败")
    elif tool in JOBS:
        job_id = payload.get("job_id") or args.get("job_id")
        if job_id:
            state.pop(identifier, None); identifier = "job:" + str(job_id)
        status = payload.get("status")
        if status == "completed" and payload.get("done") is True:
            text = "小说任务已完成。"
            if "subscription_id" in payload:
                text = "订阅已登记。"
                if payload.get("data_job_id"): text += "正文仍在后台补全，暂不代表全文下载完成。"
                if payload.get("data_update_required"): text += "正文尚需另行补全，不能认定下载完成。"
            elif "success" in payload:
                text = "章节任务已完成：新增 " + str(payload.get("success", 0)) + "，已有 " + str(payload.get("exist", 0)) + "，未获取 " + str(payload.get("fail", 0)) + "。"
        elif status == "partial": text = "章节任务部分完成，未获取 " + str(payload.get("fail", 0)) + " 章，请按缺失章节列表核对。"
        elif status == "failed": text = "小说任务失败：" + str(payload.get("error") or "请查看错误后重试")
        else: text = "订阅正在登记，尚未完成。" if str(job_id).startswith("sub-") else "小说任务仍在排队或处理中，尚未完成。"
        if payload.get("book_name"): text = "《" + str(payload["book_name"]) + "》" + text
        state[identifier] = text
    elif tool == "dmw_unsubscribe_book":
        state[identifier] = str(payload.get("message") or "取消订阅未获明确回执，请查询订阅列表核对。")
    elif tool in READS:
        incomplete = bool(payload.get("download_error") or payload.get("refresh_required") or payload.get("truncated"))
        incomplete |= payload.get("status") in {"partial", "downloading"}
        incomplete |= payload.get("complete") is False or bool(payload.get("missing_item_ids"))
        incomplete |= any(ch.get("truncated") for ch in payload.get("chapters", []) if isinstance(ch, dict))
        if incomplete:
            text = payload.get("display_text") or "正文尚未全部取得；已有内容可读，缺失或排队不代表下载完成。"
            if payload.get("download_error"):
                text += "\n下载错误：" + str(payload["download_error"].get("message") or payload["download_error"].get("code"))
            if payload.get("batch_id"): text += "\n正文仍在后台处理，完成后可重新读取原章节列表。"
            state[identifier] = text
        else:
            state.pop(identifier, None)  # Ordinary ready reads may keep the model's actual chapter response.
    else:
        status = payload.get("status")
        if payload.get("cancel_error") or status == "canceling":
            text = "取消请求仍在处理中，尚未确认已停止。"
            if payload.get("cancel_error"): text += "取消信号错误：" + str(payload["cancel_error"])
        elif status == "canceled": text = "批次已取消，已完成的正文保留。"
        elif status in {"done_with_errors", "partial"}: text = "批次部分完成，请检查错误并重新读取原章节列表；不能认定全部下载成功。"
        elif status == "done": text = "批次任务已结束，请按原章节列表重新读取核对正文。"
        elif status == "failed": text = "批次失败，未确认正文完整。"
        elif status in {"already_downloaded", "nothing_to_retry"}: text = "没有创建新的下载批次，请重新读取原章节列表核对。"
        else: text = "下载批次仍在处理中，尚未完成。"
        new_id = payload.get("batch_id")
        if "retry" in tool and new_id and str(new_id) != str(args.get("batch_id")):
            state.pop(identifier, None); identifier = "batch:" + str(new_id)
            text += "\n已提交新的重试批次，结果尚待核验。"
        state[identifier] = payload.get("display_text") or text if not payload.get("cancel_error") else text
    event.set_extra(KEY, state)


def receipt(event):
    values = event.get_extra(KEY, {})
    if not values: return None
    text = "\n\n".join(dict.fromkeys(values.values()))
    # Internal IDs remain untouched in tool arguments/state keys; only human text is abbreviated.
    uuid = r"(?:sub-)?[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"
    text = re.sub(r"(?im)^\s*(?:下载批次|新批次|任务)：\s*" + uuid + r"\s*$", "", text)
    return re.sub(uuid, "该任务", text, flags=re.I).strip() or "小说操作结果尚待核验，请查询当前状态。"


def next_receipt(event):
    """Suppress identical per-event progress responses without suppressing a new terminal result."""
    text = receipt(event)
    if text is None: return None
    if text == event.get_extra(KEY + ":emitted"):
        return ""
    event.set_extra(KEY + ":emitted", text)
    return text
