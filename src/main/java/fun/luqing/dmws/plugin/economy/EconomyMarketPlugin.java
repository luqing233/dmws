package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.*;
import fun.luqing.dmws.common.economy.*;
import fun.luqing.dmws.config.EconomyMarketProperties;
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
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyMarketPlugin {
    private final EconomyMarketService market;
    private final EconomyMarketProperties rules;

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易所|外汇帮助)$")
    @CommandInfo(cmd = "交易所", desc = "查看模拟外汇交易玩法和指令")
    public void help(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> "模拟外汇交易所｜" + market.tradingStatus()
                + "\n行情｜我的持仓｜交易记录"
                + "\n做多 EUR/USD 500 20（保证金500、杠杆20倍）"
                + "\n做空 USD/JPY 500 20｜平仓 <仓位编号>"
                + "\n交易还款 <金额或全部>"
                + "\n管理员：开启交易｜关闭交易"
                + "\n每10分钟统一模拟行情；亏损达保证金80%触发爆仓，跳空可穿仓欠款。"
                + "\n手续费=保证金×杠杆×(0.00005+杠杆×0.000005)，最低0.01；平仓不另收费。"
                + "\n保证金 " + rules.getMinimumMargin() + "～" + rules.getPositionMarginLimit() + "，总额≤" + rules.getTotalMarginLimit()
                + "；杠杆1～100倍，最多" + rules.getMaxPositions() + "仓，每日最多" + rules.getDailyOpenLimit() + "次开仓。");
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(开启交易|关闭交易)$")
    @CommandInfo(cmd = "开启交易/关闭交易", desc = "机器人主人：开关全服新开仓，关闭后仍可平仓还款")
    public void switchTrading(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> market.setTradingEnabled(event.getUserId(),
                "开启交易".equals(EconomyCommandSupport.plainText(event)), operation(event, "fx-switch", "")).displayText());
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(行情|外汇行情)$")
    @CommandInfo(cmd = "外汇行情", desc = "查看四个货币对的服务器模拟行情")
    public void quote(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> EconomyMarketText.market(market.market()));
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(我的持仓|外汇持仓)$")
    @CommandInfo(cmd = "我的持仓", desc = "查看外汇仓位、浮动盈亏和交易欠款")
    public void positions(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> EconomyMarketText.portfolio(market.portfolio(event.getUserId())));
    }

    @EconomyReadOnly @GroupMessageHandler @MessageHandlerFilter(cmd = "^(交易记录|外汇记录)$")
    @CommandInfo(cmd = "交易记录", desc = "查看最近20笔外汇结算")
    public void history(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> EconomyMarketText.history(market.history(event.getUserId())));
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^(做多|做空)(\\s+.*)?$")
    @CommandInfo(startWith = "做多", desc = "做多/做空 <货币对> <保证金> <杠杆1-100>")
    public void open(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] args = EconomyCommandSupport.plainText(event).split("\\s+");
            if (args.length != 4) throw new EconomyException("INVALID_ORDER", "用法：做多 EUR/USD 500 20，或 做空 USD/JPY 500 20");
            EconomyFxPair pair = EconomyFxPair.parse(args[1]);
            Side side = "做多".equals(args[0]) ? Side.LONG : Side.SHORT;
            int leverage;
            try { leverage = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) { throw new EconomyException("INVALID_LEVERAGE", "杠杆必须为1到100的整数"); }
            return market.open(event.getUserId(), EconomyCommandSupport.nickname(event), pair, side, amount(args[2]), leverage,
                    operation(event, "fx-open", "")).displayText();
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^平仓(\\s+.*)?$")
    @CommandInfo(startWith = "平仓", desc = "平仓 <仓位编号>，按服务器当前模拟价格结算")
    public void close(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            long id;
            try { id = Long.parseLong(EconomyCommandSupport.argument(event, "平仓").replaceFirst("^#", "")); }
            catch (NumberFormatException e) { throw new EconomyException("INVALID_POSITION", "用法：平仓 <仓位编号>"); }
            return market.close(event.getUserId(), EconomyCommandSupport.nickname(event), id, operation(event, "fx-close", Long.toString(id))).displayText();
        });
    }

    @GroupMessageHandler @MessageHandlerFilter(cmd = "^交易还款(\\s+.*)?$")
    @CommandInfo(startWith = "交易还款", desc = "交易还款 <金额或全部>，不会自动借贷")
    public void repay(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String raw = EconomyCommandSupport.argument(event, "交易还款");
            BigDecimal amount = "全部".equals(raw) ? null : amount(raw);
            return market.repay(event.getUserId(), EconomyCommandSupport.nickname(event), amount, operation(event, "fx-repay", "")).displayText();
        });
    }

    private String operation(GroupMessageEvent event, String action, String detail) {
        return EconomyMessageOperation.id(action, event.getGroupId(), event.getMessageId(), event.getUserId(), detail);
    }
    public static BigDecimal amount(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new EconomyException("INVALID_AMOUNT", "请输入最多两位小数的正数金额");
        return EconomyMoney.positive(new BigDecimal(raw));
    }
}
