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
import org.springframework.transaction.annotation.Propagation;

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
    private fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankLoanRepository loanRepository;

    @Test
    void overdueLoanCollectsWalletThenMainBankWithoutDoubleCharge() {
        long owner = 95101L;
        long borrower = 95102L;
        adminService.adjust(owner, owner, "owner", new BigDecimal("150000"), true, "overdue-owner");
        bankingService.deposit(owner, "owner", new BigDecimal("150000"), "overdue-owner-bank");
        privateBankService.create(owner, "owner", "overduetest", "逾期测试银行", "overdue-create");
        var loan = privateBankService.borrow(borrower, "borrower", "overduetest",
                new BigDecimal("500"), 1, "overdue-borrow");
        adminService.adjust(borrower, borrower, "borrower", new BigDecimal("50"), true, "overdue-extra");
        bankingService.deposit(borrower, "borrower", new BigDecimal("300"), "overdue-deposit");
        var entity = loanRepository.findById(loan.id()).orElseThrow();
        entity.setDueAt(java.time.LocalDateTime.now().minusDays(1));
        privateBankService.markOverdueLoans();
        assertEquals(fun.luqing.dmws.enums.EconomyLoanStatus.OVERDUE, entity.getStatus());
        privateBankService.settleOverdueLoan(loan.id());
        assertEquals(fun.luqing.dmws.enums.EconomyLoanStatus.REPAID, entity.getStatus());
        assertEquals(new BigDecimal("0.00"), entity.getRemainingDue());
        assertEquals(new BigDecimal("0.00"), bankingService.balances(borrower).wallet());
        assertEquals(new BigDecimal("0.00"), bankingService.balances(borrower).bank());
        assertEquals(new BigDecimal("100050.00"), privateBankService.info("overduetest").capital());
        privateBankService.settleOverdueLoan(loan.id());
        assertEquals(new BigDecimal("100050.00"), privateBankService.info("overduetest").capital());
    }

    @Test
    void extractedLedgerRollsBackBalancesAndEntriesTogether() {
        long sender = 95201L;
        long receiver = 95202L;
        adminService.adjust(sender, sender, "sender", new BigDecimal("100"), true, "rollback-seed");
        bankingService.transfer(sender, "sender", receiver, new BigDecimal("40"), "rollback-transfer");
        assertEquals(new BigDecimal("40.00"), ledgerService.balance(receiver, EconomyAccountScope.WALLET).balance());
        org.springframework.test.context.transaction.TestTransaction.flagForRollback();
        org.springframework.test.context.transaction.TestTransaction.end();
        org.springframework.test.context.transaction.TestTransaction.start();
        assertEquals(new BigDecimal("0.00"), ledgerService.balance(sender, EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("0.00"), ledgerService.balance(receiver, EconomyAccountScope.WALLET).balance());
        assertTrue(ledgerService.recentEntries(sender, EconomyAccountScope.WALLET, 100).isEmpty());
        assertTrue(ledgerService.recentEntries(receiver, EconomyAccountScope.WALLET, 100).isEmpty());
    }

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
    private fun.luqing.dmws.service.dmw.economy.EconomyOperationService operationService;

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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateItemUseAfterLastItemIsConsumedDoesNotApplyEffectAgain() {
        long userId = 96601L;
        adminService.adjust(userId, userId, "mcp-user", new BigDecimal("2000"), true, "use-last-seed");
        itemService.buy(userId, "mcp-user", "red-bull", 1, "use-last-buy");
        itemService.use(userId, "mcp-user", "red-bull", 1, "mcp:use:last-item");
        assertThrows(DuplicateEconomyOperationException.class,
                () -> itemService.use(userId, "mcp-user", "red-bull", 1, "mcp:use:last-item"));
        assertTrue(itemService.backpack(userId).isEmpty());
        assertEquals(1, profileService.get(userId).getRobBoostCharges());
        assertTrue(operationService.isProcessed("mcp:use:last-item"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedItemEffectRollsBackInventoryAndOperationAllowingCorrectedRetry() {
        long userId = 96602L;
        adminService.adjust(userId, userId, "mcp-user", new BigDecimal("10000"), true, "use-rollback-seed");
        itemService.buy(userId, "mcp-user", "star-title-card", 2, "use-rollback-buy");
        BigDecimal walletBefore = bankingService.balances(userId).wallet();
        EconomyException exception = assertThrows(EconomyException.class,
                () -> itemService.use(userId, "mcp-user", "star-title-card", 2, "mcp:use:rollback-effect"));
        assertEquals("INVALID_QUANTITY", exception.getCode());
        assertEquals(2, itemService.backpack(userId).getFirst().getQuantity());
        assertTrue(titleService.owned(userId).isEmpty());
        assertTrue(!operationService.isProcessed("mcp:use:rollback-effect"));
        itemService.use(userId, "mcp-user", "star-title-card", 1, "mcp:use:rollback-effect");
        assertEquals(1, itemService.backpack(userId).getFirst().getQuantity());
        assertTrue(titleService.owned(userId).stream().anyMatch(title -> title.getTitleCode().equals("star-traveler")));
        assertEquals(walletBefore, bankingService.balances(userId).wallet());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentItemUseWithSameRequestConsumesOnlyOnce() throws Exception {
        long userId = 96603L;
        adminService.adjust(userId, userId, "mcp-bot", new BigDecimal("2000"), true, "use-race-seed");
        itemService.buy(userId, "mcp-bot", "red-bull", 2, "use-race-buy");
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> use = () -> {
            start.await();
            try {
                itemService.use(userId, "mcp-bot", "red-bull", 1, "mcp:use:race");
                return "OK";
            } catch (DuplicateEconomyOperationException duplicate) {
                return duplicate.getCode();
            }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(use);
            var second = executor.submit(use);
            start.countDown();
            var results = java.util.List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("OK"::equals).count());
            assertEquals(1, results.stream().filter("DUPLICATE_OPERATION"::equals).count());
        }
        assertEquals(1, itemService.backpack(userId).getFirst().getQuantity());
        assertEquals(1, profileService.get(userId).getRobBoostCharges());
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
        assertEquals(0, profile.getRobBoostCharges());
        assertEquals(100, itemService.equipment(userId).batons().getFirst().energy());
        assertEquals(1, itemService.backpack(userId).stream().filter(i -> i.getItemCode().equals("electric-baton")).findFirst().orElseThrow().getQuantity());
        assertEquals(1, profile.getFishingLuckCharges());
    }

    @Test
    void itemPurchaseTopUpsWalletFromMainBankInOneTransaction() {
        long userId = 80015L;
        adminService.adjust(
                userId,
                userId,
                "buyer",
                new BigDecimal("1000"),
                true,
                "bank-fallback-seed"
        );
        bankingService.deposit(
                userId,
                "buyer",
                new BigDecimal("900"),
                "bank-fallback-deposit"
        );

        EconomyItemService.PurchaseResult purchase = itemService.buy(
                userId,
                "buyer",
                "red-bull",
                1,
                "bank-fallback-buy"
        );

        assertEquals(new BigDecimal("788.00"), purchase.bankTopUp());
        assertEquals(new BigDecimal("0.00"), purchase.walletBalance());
        assertEquals(new BigDecimal("112.00"), purchase.bankBalance());
        assertEquals(
                new BigDecimal("0.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertEquals(
                new BigDecimal("112.00"),
                ledgerService.balance(userId, EconomyAccountScope.MAIN_BANK).balance()
        );
        assertEquals(
                1,
                itemService.backpack(userId).stream()
                        .filter(item -> item.getItemCode().equals("red-bull"))
                        .findFirst()
                        .orElseThrow()
                        .getQuantity()
        );
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
        assertEquals("测试群聊", entry.groupDisplay());
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
