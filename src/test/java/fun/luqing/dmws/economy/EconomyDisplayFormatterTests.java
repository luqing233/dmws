package fun.luqing.dmws.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.plugin.economy.EconomyDisplayFormatter;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyDisplayFormatterTests {

    @Test
    void leaderboardShowsNicknameGroupAndGroupedMoney() {
        EconomyBankingService.LeaderboardEntry entry =
                new EconomyBankingService.LeaderboardEntry(
                        1L,
                        "伊蕾娜",
                        123456L,
                        "测试群聊",
                        new BigDecimal("20000")
                );

        String content = EconomyDisplayFormatter.leaderboard(List.of(entry)).get(1);

        assertTrue(content.contains("用户：伊蕾娜"));
        assertTrue(content.contains("开户群：测试群聊"));
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("123456"));
        assertTrue(content.contains("主银行存款：20,000.00 金币"));
    }

    @Test
    void itemShopShowsCodeEffectAndPurchaseExample() {
        EconomyItemDefinition item = new EconomyItemDefinition();
        item.setCode("double-sign-card");
        item.setName("双倍签到卡");
        item.setPrice(new BigDecimal("200"));
        item.setEffectType(EconomyItemEffectType.SIGN_BONUS);
        item.setEffectValue(new BigDecimal("2"));
        item.setDescription("下一次签到时自动消耗");

        String content = EconomyDisplayFormatter.itemShop(List.of(item)).get(1);

        assertTrue(content.contains("编码：double-sign-card"));
        assertTrue(content.contains("价格：200.00 金币"));
        assertTrue(content.contains("签到自动生效，奖励 ×2.00"));
        assertTrue(content.contains("购买：购买 double-sign-card [数量]"));
    }
}
