from pathlib import Path

from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Star, Context, register
from .guard import start, finish, final_receipt, WRITES


@register("dmw_economy_guard", "DMW", "可信玩家身份与经济操作回执", "1.0.0")
class DmwEconomyGuard(Star):
    def __init__(self, context: Context):
        super().__init__(context)
        self.secret = Path("/AstrBot/data/dmw-economy-context.key").read_text().strip()
        if len(self.secret) < 32:
            raise ValueError("DMW trusted context key is missing or invalid")

    @filter.on_using_llm_tool()
    async def before_tool(self, event: AstrMessageEvent, tool, tool_args):
        if tool.name in WRITES:
            start(event, tool.name, tool_args, self.secret)

    @filter.on_llm_tool_respond()
    async def after_tool(self, event: AstrMessageEvent, tool, tool_args, tool_result):
        finish(event, tool.name, tool_args or {}, tool_result)

    @filter.on_decorating_result(priority=-10000)
    async def enforce_receipt(self, event: AstrMessageEvent):
        receipt = final_receipt(event)
        if receipt:
            event.set_result(event.plain_result(receipt))
