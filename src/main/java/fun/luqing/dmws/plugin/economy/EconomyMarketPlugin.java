package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.*;
import fun.luqing.dmws.common.economy.*;
import fun.luqing.dmws.config.EconomyMarketProperties;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.service.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Shiro
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyMarketPlugin {
    private final EconomyMarketService market;
    private final EconomyMarketProperties rules;
    private final DmwConfig config;
    private final EconomyMarketHistoryService priceHistory;
    private final EconomyMarketChartService charts;

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*(帮助)?|交易所|外汇帮助)$")
    @CommandInfo(cmd = "交易帮助", desc = "查看模拟外汇交易玩法和指令")
    public void help(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> "模拟外汇交易所｜" + market.tradingStatus()
                + "\n交易行情｜交易持仓｜交易记录"
                + "\n交易历史行情 EUR/USD [24小时|7天|30天]（默认24小时，含采样K线图）"
                + "\n\n交易做多 EUR/USD 500 20（保证金500、杠杆20倍，名义仓位10000）"
                + "\n交易做空 USD/JPY 500 20\n交易平仓 <仓位编号>"
                + "\n交易补仓 <仓位编号> <追加保证金>\n交易还款 <金额或全部>"
                + "\n\n管理员：交易开启｜交易关闭"
                + "\n每10分钟统一模拟行情；亏损60%时@提醒，80%时最多5分钟补仓，逾期或补不足按触发价强平。"
                + "\n手续费=保证金×杠杆×(0.00005+杠杆×0.000005)，最低0.01；平仓不另收费。"
                + "\n保证金 " + rules.getMinimumMargin() + "～" + rules.getPositionMarginLimit() + "，总额≤" + rules.getTotalMarginLimit()
                + "；杠杆1～100倍，最多" + rules.getMaxPositions() + "仓，每日最多" + rules.getDailyOpenLimit() + "次开仓。");
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*(开启|关闭)|开启交易|关闭交易)$")
    @CommandInfo(cmd = "交易开启/交易关闭", desc = "机器人主人：开关全服新开仓，关闭后仍可平仓还款")
    public void switchTrading(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> market.setTradingEnabled(event.getUserId(),
                java.util.Set.of("开启", "开启交易").contains(command(event)), operation(event, "fx-switch", "")).displayText());
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*行情|行情|外汇行情)$")
    @CommandInfo(cmd = "交易行情", desc = "查看四个货币对的服务器模拟行情")
    public void quote(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> EconomyMarketText.market(market.market()));
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*(持仓|我的持仓)|我的持仓|外汇持仓)$")
    @CommandInfo(cmd = "交易持仓", desc = "查看外汇仓位、浮动盈亏和交易欠款")
    public void positions(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> EconomyMarketText.portfolio(market.portfolio(event.getUserId())));
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*记录|外汇记录)$")
    @CommandInfo(cmd = "交易记录", desc = "查看最近20笔外汇结算")
    public void history(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> EconomyMarketText.history(market.history(event.getUserId())));
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^交易\\s*历史行情(\\s+.*)?$")
    @CommandInfo(startWith = "交易历史行情", desc = "交易历史行情 <货币对> [24小时|7天|30天]，查看采样K线图")
    public void priceHistory(Bot bot, GroupMessageEvent event) {
        // Reuse the same forward error handling as other trade commands; images remain indivisible nodes.
        execute(bot, event, () -> {
            String[] args = command(event).split("\\s+");
            if (args.length < 2 || args.length > 3)
                throw new EconomyException("INVALID_HISTORY", "用法：交易历史行情 EUR/USD [24小时|7天|30天]");
            var pair = EconomyFxPair.parse(args[1]);
            var period = args.length == 2 ? EconomyMarketHistoryService.Period.DAY : EconomyMarketHistoryService.Period.parse(args[2]);
            var view = priceHistory.query(pair, period);
            var nodes = new java.util.ArrayList<>(EconomyDisplayFormatter.market("历史行情", EconomyMarketText.priceHistory(view)));
            if (view.count() > 0) {
                try {
                    byte[] png = charts.render(view);
                    nodes.add("【交易｜历史走势】\n" + com.mikuac.shiro.common.utils.MsgUtils.builder().img(png).build());
                } catch (RuntimeException e) {
                    log.error("历史行情K线图生成失败", e);
                    nodes.add("【交易｜图表暂不可用】\n行情查询成功，K线图生成失败；以下保留真实快照记录。");
                }
                nodes.addAll(EconomyDisplayFormatter.market("最近10轮", EconomyMarketText.recentPrices(view)));
            }
            EconomyCommandSupport.sendForward(bot, event, config, nodes);
            return null;
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*)?(做多|做空)(\\s+.*)?$")
    @CommandInfo(startWith = "交易做多", desc = "做多/做空 <货币对> <保证金> <杠杆1-100>")
    public void open(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> {
            String[] args = command(event).split("\\s+");
            if (args.length != 4) throw new EconomyException("INVALID_ORDER", "用法：交易做多 EUR/USD 500 20，或 交易做空 USD/JPY 500 20");
            EconomyFxPair pair = EconomyFxPair.parse(args[1]);
            Side side = "做多".equals(args[0]) ? Side.LONG : Side.SHORT;
            int leverage;
            try { leverage = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) { throw new EconomyException("INVALID_LEVERAGE", "杠杆必须为1到100的整数"); }
            return market.openInGroup(event.getUserId(), EconomyCommandSupport.nickname(event), pair, side, amount(args[2]), leverage,
                    operation(event, "fx-open", ""), event.getGroupId()).displayText();
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*)?(补仓|追加保证金)(\\s+.*)?$")
    @CommandInfo(startWith = "交易补仓", desc = "补仓 <仓位编号> <追加保证金>，名义仓位不变")
    public void addMargin(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> {
            String[] args = command(event).split("\\s+");
            if (args.length != 3) throw new EconomyException("INVALID_ORDER", "用法：交易补仓 <仓位编号> <追加保证金>");
            long id;
            try { id = Long.parseLong(args[1].replaceFirst("^#", "")); }
            catch (NumberFormatException e) { throw new EconomyException("INVALID_POSITION", "仓位编号必须为正整数"); }
            return market.addMargin(event.getUserId(), EconomyCommandSupport.nickname(event), id, amount(args[2]),
                    operation(event, "fx-margin", "")).displayText();
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易\\s*)?(平仓|不补仓|放弃补仓)(\\s+.*)?$")
    @CommandInfo(startWith = "交易平仓", desc = "平仓 <仓位编号>，按服务器当前模拟价格结算")
    public void close(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> {
            long id;
            try { id = Long.parseLong(argument(event, "平仓", "不补仓", "放弃补仓").replaceFirst("^#", "")); }
            catch (NumberFormatException e) { throw new EconomyException("INVALID_POSITION", "用法：交易平仓 <仓位编号>"); }
            return market.close(event.getUserId(), EconomyCommandSupport.nickname(event), id, operation(event, "fx-close", Long.toString(id))).displayText();
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^交易\\s*还款(\\s+.*)?$")
    @CommandInfo(startWith = "交易还款", desc = "交易还款 <金额或全部>，不会自动借贷")
    public void repay(Bot bot, GroupMessageEvent event) {
        execute(bot, event, () -> {
            String raw = argument(event, "还款");
            BigDecimal amount = "全部".equals(raw) ? null : amount(raw);
            return market.repay(event.getUserId(), EconomyCommandSupport.nickname(event), amount, operation(event, "fx-repay", "")).displayText();
        });
    }

    private static String command(GroupMessageEvent event) {
        String raw = EconomyCommandSupport.plainText(event).trim();
        if (raw.equals("开启交易") || raw.equals("关闭交易")) return raw;
        return raw.startsWith("交易") ? raw.substring(2).stripLeading() : raw;
    }

    private static String argument(GroupMessageEvent event, String... prefixes) {
        String raw = command(event);
        for (String prefix : prefixes) if (raw.startsWith(prefix)) return raw.substring(prefix.length()).trim();
        return "";
    }

    private void execute(Bot bot, GroupMessageEvent event, java.util.function.Supplier<String> action) {
        String text;
        String category = command(event).split("\\s+", 2)[0];
        if (category.isBlank()) category = "帮助";
        try { text = action.get(); }
        catch (EconomyException e) { category = "操作未完成"; text = e.getMessage(); }
        catch (RuntimeException e) {
            log.error("交易命令处理失败", e);
            category = "结果待确认"; text = "交易处理失败，请查询交易持仓或交易记录确认结果，勿重复操作。";
        }
        if (text != null) EconomyCommandSupport.sendForward(bot, event, config, EconomyDisplayFormatter.market(category, text));
    }

    private String operation(GroupMessageEvent event, String action, String detail) {
        return EconomyMessageOperation.id(action, event.getGroupId(), event.getMessageId(), event.getUserId(), detail);
    }
    public static BigDecimal amount(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new EconomyException("INVALID_AMOUNT", "请输入最多两位小数的正数金额");
        return EconomyMoney.positive(new BigDecimal(raw));
    }
}
