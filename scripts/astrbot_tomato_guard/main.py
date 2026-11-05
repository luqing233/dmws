import json
from pathlib import Path
from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Star, Context, register
from .guard import start, finish, next_receipt


@register("dmw_tomato_guard", "DMW", "小说下载与取消重试真实回执", "1.0.2")
class TomatoGuard(Star):
    def __init__(self, context: Context):
        super().__init__(context)
        policy = Path("/AstrBot/data/dmw-tomato-policy.json")
        self.masters = json.loads(policy.read_text()).get("masters", []) if policy.exists() else []

    @filter.on_using_llm_tool()
    async def before(self, event: AstrMessageEvent, tool, tool_args):
        start(event, tool.name, tool_args if tool_args is not None else {}, self.masters)

    @filter.on_llm_tool_respond()
    async def after(self, event: AstrMessageEvent, tool, tool_args, tool_result):
        finish(event, tool.name, tool_args or {}, tool_result)

    @filter.on_decorating_result(priority=-9000)
    async def enforce(self, event: AstrMessageEvent):
        text = next_receipt(event)
        if text is not None:
            event.set_result(event.plain_result(text) if text else event.make_result())
