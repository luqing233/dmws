package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.service.dmw.economy.EconomyAdminService;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishingAndRobTests {

    @Autowired
    private EconomyAdminService adminService;

    @Autowired
    private EconomyLedgerService ledgerService;

    @Autowired
    private EconomyFishingService fishingService;

    @Autowired
    private EconomyRobService robService;

    @Test
    void fishingChargesOnceAndEnforcesCooldown() {
        long userId = 94001L;
        adminService.adjust(userId, userId, "fisher", new BigDecimal("1000"), true, "fishing-seed");
        fishingService.buyRod(userId, "fisher", "fishing-rod-buy");

        EconomyFishingService.FishingResult result =
                fishingService.fish(userId, "fisher", "fishing-action");
        assertEquals(new BigDecimal("20.00"), result.cost());
        assertTrue(result.walletBalance().compareTo(new BigDecimal("480.00")) >= 0);
        assertThrows(
                EconomyException.class,
                () -> fishingService.fish(userId, "fisher", "fishing-action-2")
        );
    }

    @Test
    void rodUpgradePersistsAndChargesTheExpectedPrice() {
        long userId = 94002L;
        adminService.adjust(userId, userId, "fisher", new BigDecimal("2000"), true, "rod-seed");
        fishingService.buyRod(userId, "fisher", "fish-rod-buy");

        assertEquals(2, fishingService.upgradeRod(userId, "fisher", "rod-upgrade"));
        assertEquals(
                new BigDecimal("500.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
    }

    @Test
    void robbingAnEmptyAccountReturnsPoorTargetFeedbackWithoutFine() {
        long robber = 94003L;
        long target = 94004L;
        adminService.adjust(robber, robber, "robber", new BigDecimal("100"), true, "rob-seed");

        EconomyRobService.RobResult result = robService.rob(robber, "robber", target, "rob-action");
        assertFalse(result.success());
        assertTrue(result.poorTarget());
        assertEquals(new BigDecimal("0.00"), result.fine());
        assertEquals(
                new BigDecimal("100.00"),
                ledgerService.balance(robber, EconomyAccountScope.WALLET).balance()
        );
        assertThrows(
                EconomyException.class,
                () -> robService.rob(robber, "robber", target, "rob-action-2")
        );
    }
}
