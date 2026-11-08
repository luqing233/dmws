package fun.luqing.dmws.service.dmw.economy;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static fun.luqing.dmws.service.dmw.economy.EconomyMarketService.*;

public final class EconomyMarketText {
    private EconomyMarketText() { }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public static String market(MarketView view) {
        if (view.quotes().isEmpty()) return "模拟外汇行情尚未就绪";
        StringBuilder text = new StringBuilder("模拟外汇｜每10分钟统一更新\n")
                .append(TIME.format(view.quotedAt())).append(view.fresh() ? "" : "（行情过期，暂停成交）");
        for (var q : view.quotes()) text.append("\n").append(q.pair()).append(" ").append(price(q.price()))
                .append(" ｜本轮 ").append(q.change().signum() >= 0 ? "+" : "")
                .append(q.change().movePointRight(2).setScale(3, RoundingMode.HALF_UP)).append("%");
        return text.append("\n服务器模拟走势，非真实汇率；可做多或做空").toString();
    }

    public static String portfolio(Portfolio view) {
        StringBuilder text = new StringBuilder("我的外汇持仓");
        if (view.quotedAt() != null) text.append("｜").append(TIME.format(view.quotedAt()));
        if (!view.fresh()) text.append("（行情未就绪或过期）");
        text.append("\n保证金 ").append(money(view.margin())).append("｜浮动盈亏 ").append(signed(view.floatingPnl()));
        if (view.debt().signum() > 0) text.append("\n交易欠款 ").append(money(view.debt()));
        text.append("\n交易权益 ").append(money(view.tradingEquity())).append("（不含钱包/银行）");
        if (view.positions().isEmpty()) text.append("\n暂无持仓");
        for (var p : view.positions()) {
            text.append("\n\n#").append(p.id()).append(" ").append(p.pair()).append(" ").append(direction(p.side())).append(" ").append(p.leverage()).append("倍")
                    .append("\n保证金 ").append(money(p.margin())).append("｜开仓费 ").append(money(p.fee()))
                    .append("\n开仓 ").append(price(p.entry())).append(" → 当前 ").append(price(p.current()))
                    .append("\n浮盈亏 ").append(signed(p.pnl())).append("｜扣费净收益 ").append(signed(p.netProfit()));
        }
        return text.toString();
    }

    public static String history(List<PositionView> entries) {
        if (entries.isEmpty()) return "暂无外汇结算记录";
        StringBuilder text = new StringBuilder("最近外汇结算（最多20笔）");
        for (var p : entries) text.append("\n\n").append(TIME.format(p.closedAt())).append("\n").append(p.closeReceipt());
        return text.toString();
    }
}
