package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyPrizeType;
import fun.luqing.dmws.enums.EconomyRedPacketKind;
import fun.luqing.dmws.plugin.economy.EconomyAccountPlugin;
import fun.luqing.dmws.repository.dmw.economy.EconomyRafflePrizeRepository;
import fun.luqing.dmws.service.dmw.economy.EconomyAdminService;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyItemService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyPrivateBankService;
import fun.luqing.dmws.service.dmw.economy.EconomyRaffleService;
import fun.luqing.dmws.service.dmw.economy.EconomyRedPacketService;
import fun.luqing.dmws.service.dmw.economy.EconomySignService;
import fun.luqing.dmws.service.dmw.economy.EconomyTitleService;
import fun.luqing.dmws.service.dmw.economy.EconomyUserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyServiceIntegrationTests {

    @Autowired
    private EconomyAdminService adminService;

    @Autowired
    private EconomyLedgerService ledgerService;

    @Autowired
    private EconomyBankingService bankingService;

    @Autowired
    private EconomySignService signService;

    @Autowired
    private EconomyItemService itemService;

    @Autowired
    private EconomyTitleService titleService;

    @Autowired
    private EconomyRedPacketService redPacketService;

    @Autowired
    private EconomyRaffleService raffleService;

    @Autowired
    private EconomyPrivateBankService privateBankService;

    @Autowired
    private EconomyRafflePrizeRepository rafflePrizeRepository;

    @Autowired
    private EconomyUserProfileService profileService;

    @Autowired
    private EconomyAccountPlugin economyAccountPlugin;

    @Test
    void duplicateOperationDoesNotChangeBalanceTwice() {
        long userId = 80001L;
        String operationId = "test-credit";

        adminService.adjust(userId, userId, "tester", new BigDecimal("100"), true, operationId);
        assertThrows(
                DuplicateEconomyOperationException.class,
                () -> adminService.adjust(userId, userId, "tester", new BigDecimal("100"), true, operationId)
        );

        assertEquals(
                new BigDecimal("100.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
    }

    @Test
    void depositAndWithdrawMoveMoneyBetweenWalletAndMainBank() {
        long userId = 80002L;
        adminService.adjust(
                userId,
                userId,
                "tester",
                new BigDecimal("300"),
                true,
                "seed-deposit-user"
        );

        EconomyBankingService.Balances deposited = bankingService.deposit(
                userId,
                "tester",
                new BigDecimal("120"),
                "deposit"
        );
        assertEquals(new BigDecimal("180.00"), deposited.wallet());
        assertEquals(new BigDecimal("120.00"), deposited.bank());

        EconomyBankingService.Balances withdrawn = bankingService.withdraw(
                userId,
                "tester",
                new BigDecimal("20"),
                "withdraw"
        );
        assertEquals(new BigDecimal("200.00"), withdrawn.wallet());
        assertEquals(new BigDecimal("100.00"), withdrawn.bank());
    }

    @Test
    void signCanOnlySucceedOncePerDayAndCreditsWalletInSameTransaction() {
        long userId = 80003L;
        EconomySignService.SignResult result = signService.sign(userId, "tester", "sign-1");

        assertTrue(result.reward().compareTo(new BigDecimal("50")) >= 0);
        assertEquals(
                result.walletBalance(),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertThrows(
                EconomyException.class,
                () -> signService.sign(userId, "tester", "sign-2")
        );
    }

    @Test
    void limitedRafflePrizeIsConsumedOnceAndCannotBeDrawnAgain() {
        long userId = 80004L;
        adminService.adjust(userId, userId, "tester", new BigDecimal("5000"), true, "seed-raffle");

        rafflePrizeRepository.deleteAll();
        EconomyRafflePrize prize = new EconomyRafflePrize();
        prize.setCode("test-money");
        prize.setName("测试奖金");
        prize.setDescription("测试");
        prize.setType(EconomyPrizeType.MONEY);
        prize.setWeight(1);
        prize.setStock(1);
        prize.setMoneyAmount(new BigDecimal("200"));
        prize.setEnabled(true);
        rafflePrizeRepository.saveAndFlush(prize);

        EconomyRaffleService.DrawResult first = raffleService.draw(userId, "tester", 1, "raffle-1");
        assertEquals(1, first.prizes().size());
        assertEquals(
                new BigDecimal("4200.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertEquals(
                0,
                rafflePrizeRepository.findByCode("test-money").orElseThrow().getStock()
        );
        assertThrows(
                EconomyException.class,
                () -> raffleService.draw(userId, "tester", 1, "raffle-2")
        );
    }

    @Test
    void raffleTitlePrizeIsGrantedAndUsesTheCorrectHandler() {
        long userId = 80011L;
        adminService.adjust(userId, userId, "tester", new BigDecimal("5000"), true, "seed-title-raffle");

        rafflePrizeRepository.deleteAll();
        EconomyRafflePrize prize = new EconomyRafflePrize();
        prize.setCode("test-title");
        prize.setName("星尘旅人");
        prize.setDescription("测试称号");
        prize.setType(EconomyPrizeType.TITLE);
        prize.setWeight(1);
        prize.setStock(1);
        prize.setTitleCode("star-traveler");
        prize.setMoneyAmount(BigDecimal.ZERO);
        prize.setEnabled(true);
        rafflePrizeRepository.saveAndFlush(prize);

        EconomyRaffleService.DrawResult result =
                raffleService.draw(userId, "tester", 1, "title-raffle");
        assertEquals(EconomyPrizeType.TITLE, result.prizes().getFirst().type());
        assertTrue(titleService.owned(userId).stream()
                .anyMatch(title -> title.getTitleCode().equals("star-traveler")));
        assertEquals(0, rafflePrizeRepository.findByCode("test-title").orElseThrow().getStock());
    }

    @Test
    void redPacketClaimsUseTheirOwnAmountsAndRemainingBalance() {
        long sender = 80005L;
        long firstClaimer = 80006L;
        long secondClaimer = 80007L;
        adminService.adjust(sender, sender, "sender", new BigDecimal("10"), true, "seed-redpacket");

        long packetId = redPacketService.create(
                sender,
                "sender",
                123456L,
                new BigDecimal("10"),
                3,
                EconomyRedPacketKind.FIXED,
                null,
                "redpacket-create"
        );
        EconomyRedPacketService.ClaimResult first = redPacketService.claim(
                firstClaimer,
                "first",
                123456L,
                packetId,
                null,
                "redpacket-claim-1"
        );
        EconomyRedPacketService.ClaimResult second = redPacketService.claim(
                secondClaimer,
                "second",
                123456L,
                packetId,
                null,
                "redpacket-claim-2"
        );

        assertEquals(2, first.remainingCount());
        assertEquals(1, second.remainingCount());
        assertEquals(
                BigDecimal.ZERO.setScale(2),
                ledgerService.balance(sender, EconomyAccountScope.WALLET).balance()
        );
        assertTrue(EconomyMoney.normalize(first.amount().add(second.amount()))
                .compareTo(new BigDecimal("10.00")) < 0);
    }

    @Test
    void itemPurchaseAndTitleUnlockAreAtomicWithWalletChanges() {
        long userId = 80008L;
        adminService.adjust(userId, userId, "collector", new BigDecimal("5000"), true, "seed-items");

        itemService.buy(userId, "collector", "double-sign-card", 1, "buy-double-card");
        assertEquals(
                new BigDecimal("4912.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertEquals(
                1,
                itemService.backpack(userId).stream()
                        .filter(item -> item.getItemCode().equals("double-sign-card"))
                        .findFirst()
                        .orElseThrow()
                        .getQuantity()
        );

        EconomySignService.SignResult sign = signService.sign(userId, "collector", "sign-with-card");
        assertTrue(sign.bonusText().contains("双倍"));
        assertTrue(itemService.backpack(userId).stream()
                .noneMatch(item -> item.getItemCode().equals("double-sign-card")));

        itemService.buy(userId, "collector", "star-title-card", 1, "buy-title-card");
        String result = itemService.use(userId, "collector", "star-title-card", 1, "use-title-card");
        assertTrue(result.contains("星尘旅人"));
        assertTrue(titleService.owned(userId).stream()
                .anyMatch(title -> title.getTitleCode().equals("star-traveler")));
    }

    @Test
    void huYanSupportItemsApplyTheirConfiguredEffects() {
        long userId = 80013L;
        adminService.adjust(userId, userId, "items", new BigDecimal("5000"), true, "support-item-seed");

        itemService.buy(userId, "items", "electric-baton", 1, "buy-baton");
        itemService.buy(userId, "items", "fishing-luck-bait", 1, "buy-luck-bait");
        itemService.use(userId, "items", "electric-baton", 1, "use-baton");
        itemService.use(userId, "items", "fishing-luck-bait", 1, "use-luck-bait");

        var profile = profileService.get(userId);
        assertEquals(1, profile.getRobBoostCharges());
        assertEquals(1, profile.getFishingLuckCharges());
    }

    @Test
    void makeupCardRestoresBrokenSignStreak() {
        long userId = 80014L;
        var profile = profileService.getOrCreateForUpdate(userId, "signer");
        profile.setSignStreak(1);
        profile.setOldSignNumber(4);
        itemService.addItem(userId, "sign-in-card", 1);

        String result = itemService.use(userId, "signer", "sign-in-card", 1, "use-makeup-card");

        assertTrue(result.contains("恢复连续签到 4 天"));
        assertEquals(5, profile.getSignStreak());
        assertEquals(0, profile.getOldSignNumber());
    }

    @Test
    void privateBankCreationDepositLoanAndRepaymentStayInOneLedger() {
        long ownerId = 80009L;
        long customerId = 80010L;
        adminService.adjust(ownerId, ownerId, "owner", new BigDecimal("150000"), true, "bank-seed-owner");
        bankingService.deposit(ownerId, "owner", new BigDecimal("150000"), "bank-seed-main");

        EconomyPrivateBankService.BankView bank = privateBankService.create(
                ownerId,
                "owner",
                "testbank",
                "测试银行",
                "private-bank-create-test"
        );
        assertEquals(new BigDecimal("100000.00"), bank.capital());

        adminService.adjust(
                customerId,
                customerId,
                "customer",
                new BigDecimal("1000"),
                true,
                "bank-seed-customer"
        );
        EconomyPrivateBankService.LoanView loan = privateBankService.borrow(
                customerId,
                "customer",
                "testbank",
                new BigDecimal("500"),
                7,
                "private-bank-loan-test"
        );
        assertEquals(new BigDecimal("550.00"), loan.totalDue());
        assertEquals(
                new BigDecimal("1500.00"),
                ledgerService.balance(customerId, EconomyAccountScope.WALLET).balance()
        );

        EconomyPrivateBankService.RepaymentView repayment = privateBankService.repay(
                customerId,
                "customer",
                "testbank",
                new BigDecimal("550"),
                "private-bank-repay-test"
        );
        assertEquals(new BigDecimal("550.00"), repayment.repaid());
        assertEquals(
                new BigDecimal("950.00"),
                ledgerService.balance(customerId, EconomyAccountScope.WALLET).balance()
        );
        assertEquals(
                new BigDecimal("100050.00"),
                ledgerService.accountBalance("bank:testbank:capital", EconomyAccountScope.PLUGIN)
        );

        privateBankService.setDefaultBank(customerId, "customer", "testbank");
        assertEquals("testbank", privateBankService.defaultBank(customerId).code());
        privateBankService.deposit(
                customerId,
                "customer",
                "testbank",
                new BigDecimal("100"),
                "private-bank-default-deposit"
        );
        assertEquals(
                new BigDecimal("100.00"),
                privateBankService.depositBalance(customerId, "testbank")
        );
    }

    @Test
    void bankLeaderboardShowsNicknameAndRegisteredGroup() {
        long userId = 80012L;
        profileService.rememberGroup(userId, "排行榜用户", 123456789L, "测试群聊");
        adminService.adjust(userId, userId, "排行榜用户", new BigDecimal("500"), true, "ranking-seed");
        bankingService.deposit(userId, "排行榜用户", new BigDecimal("300"), "ranking-deposit");

        EconomyBankingService.LeaderboardEntry entry = bankingService.bankLeaderboard(10)
                .stream()
                .filter(item -> item.userId() == userId)
                .findFirst()
                .orElseThrow();

        assertEquals("排行榜用户", entry.nickname());
        assertEquals("测试群聊 (123456789)", entry.groupDisplay());
        assertEquals(new BigDecimal("300.00"), entry.balance());
    }

    @Test
    void economyGroupPresenceAspectIsAppliedToCommandPlugins() {
        assertTrue(AopUtils.isAopProxy(economyAccountPlugin));
    }

    private String operation(String type) {
        return type + "-" + UUID.randomUUID();
    }
}
