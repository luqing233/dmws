package fun.luqing.dmws.mcp;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.service.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class EconomyMarketMcpTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EconomyMarketService market;
    private final EconomyMcpTools identity;

    public ObjectNode execute(String tool, JsonNode args) {
        if ("dmw_economy_fx_market".equals(tool)) {
            var view = market.market();
            var response = success(EconomyMarketText.market(view));
            var data = response.putObject("data").put("source", "SIMULATED").put("interval_minutes", 10)
                    .put("sequence", view.sequence()).put("quoted_at", timestamp(view.quotedAt())).put("fresh", view.fresh());
            var pairs = data.putArray("pairs");
            for (var quote : view.quotes()) pairs.addObject().put("pair", quote.pair()).put("name", quote.name())
                    .put("price", quote.price().toPlainString()).put("change", quote.change().toPlainString());
            return response;
        }
        long user = identity.actingUser(args);
        if ("dmw_economy_fx_positions".equals(tool)) {
            var view = market.portfolio(user);
            var response = success(EconomyMarketText.portfolio(view));
            var data = response.putObject("data").put("user_id", user).put("quoted_at", timestamp(view.quotedAt())).put("fresh", view.fresh())
                    .put("margin", view.margin().toPlainString()).put("floating_pnl", view.floatingPnl().toPlainString())
                    .put("trading_debt", view.debt().toPlainString()).put("trading_equity", view.tradingEquity().toPlainString());
            var rows = data.putArray("positions"); view.positions().forEach(p -> rows.add(position(p)));
            return response;
        }
        if ("dmw_economy_fx_history".equals(tool)) {
            var view = market.history(user);
            var response = success(EconomyMarketText.history(view));
            var rows = response.putObject("data").put("user_id", user).putArray("records");
            view.forEach(p -> rows.add(position(p)));
            return response;
        }
        // The controller verifies HMAC via EconomyMcpContext before routing; this marker is replaced there.
        if (!args.has("_source_message_id")) throw new McpToolException("外汇写操作缺少可信消息上下文");
        String request = identity.requestId(args);
        EconomyMarketService.ActionResult result;
        switch (tool) {
            case "dmw_economy_fx_open" -> {
                EconomyFxPair pair = EconomyFxPair.parse(args.path("pair").asText());
                String sideText = args.path("side").asText();
                Side side = switch (sideText) {
                    case "long" -> Side.LONG; case "short" -> Side.SHORT;
                    default -> throw new McpToolException("side 必须为 long 或 short");
                };
                if (!args.path("leverage").isIntegralNumber() || !args.path("leverage").canConvertToInt()) throw new McpToolException("leverage 必须为整数");
                result = market.openInGroup(user, "", pair, side, amount(args.path("margin")), args.path("leverage").asInt(),
                        identity.messageOperation("fx-open", args, user, "", "fx-open", request), identity.requiredLong(args, "group_id"));
            }
            case "dmw_economy_fx_add_margin" -> {
                long position = identity.requiredLong(args, "position_id");
                if (position <= 0 || !args.path("position_id").isIntegralNumber()) throw new McpToolException("position_id 必须为正整数");
                result = market.addMargin(user, "", position, amount(args.path("amount")),
                        identity.messageOperation("fx-margin", args, user, "", "fx-margin", request));
            }
            case "dmw_economy_fx_close" -> {
                if (!args.path("position_id").isIntegralNumber()) throw new McpToolException("position_id 必须为整数");
                long position = identity.requiredLong(args, "position_id");
                if (position <= 0) throw new McpToolException("position_id 必须为正整数");
                result = market.close(user, "", position,
                        identity.messageOperation("fx-close", args, user, Long.toString(position), "fx-close", request));
            }
            case "dmw_economy_fx_repay" -> {
                BigDecimal value = "all".equals(args.path("amount").asText()) ? null : amount(args.path("amount"));
                result = market.repay(user, "", value, identity.messageOperation("fx-repay", args, user, "", "fx-repay", request));
            }
            default -> throw new McpToolException("未知外汇工具");
        }
        ObjectNode response = success(result.displayText());
        ObjectNode data = response.putObject("data");
        if (result.positionId() != null) data.put("position_id", result.positionId());
        data.put("amount", result.amount().toPlainString()).put("trading_debt", result.debt().toPlainString());
        return response;
    }

    private BigDecimal amount(JsonNode value) {
        String raw = value.asText();
        if (!raw.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new EconomyException("INVALID_AMOUNT", "金额必须为最多两位小数的正数");
        return EconomyMoney.positive(new BigDecimal(raw));
    }
    private String timestamp(java.time.LocalDateTime value) { return value == null ? null : value.toString(); }
    private ObjectNode position(EconomyMarketService.PositionView p) {
        return MAPPER.createObjectNode().put("position_id", p.id()).put("pair", p.pair())
                .put("side", p.side().name().toLowerCase(java.util.Locale.ROOT)).put("status", p.status().name().toLowerCase(java.util.Locale.ROOT))
                .put("margin", p.margin().toPlainString()).put("leverage", p.leverage()).put("notional", p.notional().toPlainString())
                .put("opening_fee", p.fee().toPlainString()).put("entry_price", p.entry().toPlainString()).put("current_price", p.current().toPlainString())
                .put("pnl", p.pnl().toPlainString()).put("net_profit", p.netProfit().toPlainString())
                .put("opened_at", timestamp(p.openedAt())).put("closed_at", timestamp(p.closedAt())).put("close_receipt", p.closeReceipt())
                .put("margin_call_deadline", timestamp(p.marginCallDeadline()));
    }
    private ObjectNode success(String text) {
        return MAPPER.createObjectNode().put("ok", true).put("code", "OK").put("message", text).put("display_text", text);
    }
}
