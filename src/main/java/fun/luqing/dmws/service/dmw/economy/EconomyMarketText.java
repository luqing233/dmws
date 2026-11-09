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
                    .append("｜名义仓位 ").append(money(p.notional()))
                    .append("\n开仓 ").append(price(p.entry())).append(" → 当前 ").append(price(p.current()))
                    .append("\n浮盈亏 ").append(signed(p.pnl())).append("｜扣费净收益 ").append(signed(p.netProfit()));
            if (p.marginCallDeadline() != null) text.append("\n补仓截止 ").append(p.marginCallDeadline().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")))
                    .append("，逾期按触发价强平；交易补仓 ").append(p.id()).append(" <金额>");
        }
        return text.toString();
    }

    public static String history(List<PositionView> entries) {
        if (entries.isEmpty()) return "暂无外汇结算记录";
        StringBuilder text = new StringBuilder("最近外汇结算（最多20笔）");
        for (var p : entries) text.append("\n\n").append(TIME.format(p.closedAt())).append("\n").append(p.closeReceipt());
        return text.toString();
    }

    public static String priceHistory(EconomyMarketHistoryService.History h) {
        String title = h.pair().code() + "｜最近" + h.period().label
                + "\n查询区间 " + TIME.format(h.start()) + " ～ " + TIME.format(h.end()) + "（游戏本地时间）";
        if (h.count() == 0) return title + "\n暂无历史快照。历史从首次生成行情开始积累，不补造数据。\n服务器模拟行情，非真实汇率。";
        return title + "\n实际数据 " + TIME.format(h.firstAt()) + " ～ " + TIME.format(h.lastAt())
                + "\n最后采样价 " + price(h.last()) + "｜区间涨跌 " + h.change().movePointRight(2).setScale(3, RoundingMode.HALF_UP).toPlainString() + "%"
                + "\n区间最高 " + price(h.high()) + "｜区间最低 " + price(h.low())
                + "\n原始快照 " + h.count() + " 条｜内部缺口 " + h.gaps() + " 处"
                + "\n图表为" + h.period().minutes + "分钟采样K线，绿涨红跌；影线仅含已观测价格。"
                + "\n开价沿用连续前轮收价，缺口后重新起算；概览统计全部原始快照。"
                + (h.partial() ? "\n数据未覆盖完整区间；缺失时段留空，不推断停机期间走势。" : "\n已覆盖查询区间。")
                + "\n涨跌以区间首条和末条快照计算；最高/最低仅限已观测价格。\n服务器模拟行情，非真实汇率。";
    }

    public static String recentPrices(EconomyMarketHistoryService.History h) {
        StringBuilder text = new StringBuilder("时间｜价格｜本轮涨跌（最新在前）");
        var recent = h.recent();
        for (int i = recent.size()-1; i >= Math.max(0, recent.size()-10); i--) {
            var row = recent.get(i);
            String change = "无前值";
            if (i > 0 && row.at().equals(recent.get(i-1).at().plusMinutes(10))) {
                var rate = row.price().divide(recent.get(i-1).price(),12,RoundingMode.HALF_UP).subtract(java.math.BigDecimal.ONE);
                change = (rate.signum() >= 0 ? "+" : "") + rate.movePointRight(2).setScale(3,RoundingMode.HALF_UP).toPlainString() + "%";
            } else if (i > 0) change = "前轮缺失";
            text.append("\n").append(TIME.format(row.at())).append("｜").append(price(row.price())).append("｜").append(change);
        }
        return text.toString();
    }
}
