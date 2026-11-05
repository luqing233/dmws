"""Trusted OneBot context and deterministic economy receipts; no network or model calls."""
import base64
import hashlib
import hmac
import json
import time

WRITES = {"dmw_economy_" + action for action in ("buy", "use_item", "transfer", "rob", "discharge", "bail")}
STATE_KEY = "dmw_economy_receipts_v1"


def sign_context(secret, tool, group_id, bot_id, sender_id, message_id, now=None):
    values = [int(group_id), int(bot_id), int(sender_id), int(message_id)]
    if len(secret) < 32 or any(x <= 0 for x in values[:3]) or not values[3]:
        raise ValueError("Invalid trusted OneBot context")
    context = dict(zip(("group_id", "bot_id", "sender_id", "message_id"), values))
    context.update(tool=tool, issued_at=int(time.time() if now is None else now))
    payload = base64.urlsafe_b64encode(json.dumps(context, separators=(",", ":")).encode()).decode().rstrip("=")
    return payload + "." + hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()


def receipt_key(tool, args):
    # Repeated attempts at the same step replace its pending/error receipt, not other steps.
    return json.dumps([tool, args.get("user_id"), args.get("item"), args.get("target_user_id"),
                       args.get("target_name"), args.get("confirm", False)], ensure_ascii=False)


def start(event, tool, args, secret):
    if tool not in WRITES:
        return
    state = dict(event.get_extra(STATE_KEY, {}))
    key = receipt_key(tool, args)
    state[key] = "操作结果尚未确认，请查询账户或状态后再试；不能认定已经成功。"
    event.set_extra(STATE_KEY, state)
    # Remove all model-supplied identity/provenance fields before adding trusted metadata.
    args.pop("_trusted_context", None)
    args.pop("_source_message_id", None)
    args["group_id"] = int(event.get_group_id())
    args["_trusted_context"] = sign_context(secret, tool, event.get_group_id(), event.get_self_id(),
                                           event.get_sender_id(), event.message_obj.message_id)


def finish(event, tool, args, result):
    if tool not in WRITES:
        return
    state = dict(event.get_extra(STATE_KEY, {}))
    text = "操作结果尚未确认，请查询账户或状态后再试；不能认定已经成功。"
    if result is not None:
        blocks = getattr(result, "content", []) or []
        payloads = []
        raw = []
        for block in blocks:
            value = getattr(block, "text", "")
            if not value:
                continue
            raw.append(value)
            try:
                payloads.append(json.loads(value))
            except (ValueError, TypeError):
                pass
        payload = next((p for p in payloads if isinstance(p, dict) and "ok" in p), None)
        if payload is not None:
            if payload.get("ok") is True and not getattr(result, "isError", False):
                text = payload.get("display_text") or payload.get("message") or "操作已完成。"
            else:
                text = "本次操作未完成：" + str(payload.get("message") or "接口返回失败")
        elif getattr(result, "isError", False):
            text = "本次操作未完成：" + ("；".join(raw)[:300] or "接口返回失败")
    state[receipt_key(tool, args)] = text
    event.set_extra(STATE_KEY, state)


def final_receipt(event):
    state = event.get_extra(STATE_KEY, {})
    return "\n\n".join(dict.fromkeys(state.values())) if state else None
