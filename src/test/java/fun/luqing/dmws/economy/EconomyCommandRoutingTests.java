package fun.luqing.dmws.economy;

import fun.luqing.dmws.plugin.economy.EconomyCommandPatterns;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyCommandRoutingTests {

    @Test
    void unifiedRepaymentAndLegacyBankRepaymentHaveDisjointRoutes() throws Exception {
        var generic = fun.luqing.dmws.plugin.economy.EconomyAccountPlugin.class
                .getMethod("repay", com.mikuac.shiro.core.Bot.class, com.mikuac.shiro.dto.event.message.GroupMessageEvent.class)
                .getAnnotation(com.mikuac.shiro.annotation.MessageHandlerFilter.class).cmd();
        var legacy = fun.luqing.dmws.plugin.economy.EconomyPrivateBankPlugin.class
                .getMethod("repay", com.mikuac.shiro.core.Bot.class, com.mikuac.shiro.dto.event.message.GroupMessageEvent.class)
                .getAnnotation(com.mikuac.shiro.annotation.MessageHandlerFilter.class).cmd();
        for (String message : java.util.List.of("还款 100", "还款 全部", "还款 !")) {
            assertTrue(matches(generic, message)); assertFalse(matches(legacy, message));
        }
        for (String message : java.util.List.of("银行还款 demo 100", "还款 demo 100")) {
            assertFalse(matches(generic, message)); assertTrue(matches(legacy, message));
        }
    }

    @Test
    void fishingRodPurchaseDoesNotMatchGenericItemPurchase() {
        assertFalse(matches(EconomyCommandPatterns.ITEM_BUY, "购买鱼竿"));
        assertFalse(matches(EconomyCommandPatterns.ITEM_BUY, "购买 鱼竿"));
        assertTrue(matches(EconomyCommandPatterns.FISHING_ROD_BUY, "购买鱼竿"));
        assertTrue(matches(EconomyCommandPatterns.FISHING_ROD_BUY, "购买 鱼竿"));
    }

    @Test
    void genericItemPurchaseStillHandlesItemsAndTitles() {
        assertTrue(matches(EconomyCommandPatterns.ITEM_BUY, "购买 双倍签到卡"));
        assertTrue(matches(EconomyCommandPatterns.ITEM_BUY, "购买道具 双倍签到卡"));
        assertTrue(matches(EconomyCommandPatterns.ITEM_BUY, "购买称号 大富翁"));
        assertTrue(matches(EconomyCommandPatterns.ITEM_BUY, "buy star-token"));
    }

    private boolean matches(String pattern, String message) {
        return Pattern.matches(pattern, message);
    }
}
