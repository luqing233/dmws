package fun.luqing.dmws.economy;

import fun.luqing.dmws.plugin.economy.EconomyCommandPatterns;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyCommandRoutingTests {

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
