package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyPrizeType;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyPenaltyTests {
    private long userId = 98801L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 14, 0);
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyRaffleService raffle;
    @Autowired EconomyRobService rob;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyCombatService combat;
    @Autowired EconomyItemService items;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyOperationService operations;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired EconomyFishingBaitStateRepository baitStates;
    @Autowired EconomyLedgerEntryRepository entries;
    @Autowired EconomyRafflePrizeRepository prizes;
    @Autowired @Qualifier("dmwTransactionManager") PlatformTransactionManager transactionManager;
    @MockitoBean EconomyFishingRandom fishingRandom;
    @MockitoBean EconomyRobRandom robRandom;
    @MockitoBean EconomyGameTime time;

    @BeforeEach
    void setup() {
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(NOW.toLocalDate());
    }

    private BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private void seed(String wallet, String bank) {
        profiles.getOrCreateForUpdate(userId, "玩家");
        String operation = "penalty-seed-" + userId;
        operations.start(operation, "TEST", userId, "测试资金");
        if (money(bank).signum() < 0) {
            ledger.chargeHospital(userId, money(bank).negate(), operation, "已有医疗欠款");
        } else if (money(bank).signum() > 0) {
            ledger.creditUser(userId, EconomyAccountScope.MAIN_BANK, money(bank), operation,
                    EconomyLedgerReason.ADMIN_ADJUST, userId, "测试存款");
        }
        if (money(wallet).signum() > 0) {
            ledger.creditUser(userId, EconomyAccountScope.WALLET, money(wallet), operation,
                    EconomyLedgerReason.ADMIN_ADJUST, userId, "测试钱包");
        }
    }

    private void prepareFishing(String code) {
        profiles.getOrCreateForUpdate(userId, "玩家").setFishingRodPurchased(true);
        items.addItem(userId, "fishing-bait-white", 1);
        events.findAll().forEach(event -> {
            event.setTickets(event.getCode().equals(code) ? 10000 : 0);
            events.save(event);
        });
    }

    private void assertBalances(String wallet, String bank) {
        assertEquals(money(wallet), ledger.balance(userId, EconomyAccountScope.WALLET).balance());
        assertEquals(money(bank), ledger.balance(userId, EconomyAccountScope.MAIN_BANK).balance());
    }

    private void assertLossLedger(String operation, EconomyLedgerReason reason, String loss) {
        var rows = entries.findAll().stream().filter(row -> row.getOperationId().equals(operation)).toList();
        assertTrue(rows.stream().allMatch(row -> row.getReason() == reason && row.getDelta().signum() < 0));
        assertEquals(money(loss).negate(), rows.stream().map(row -> row.getDelta())
                .reduce(money("0"), BigDecimal::add));
    }

    @ParameterizedTest
    @CsvSource({"boots,10", "dead-fish,30", "snag,50", "exam,80", "salamander,120"})
    void fishingFinesCollectFromBankWhenEverythingIsDeposited(String code, String fine) {
        seed("0", "200");
        prepareFishing(code);
        var result = FishingTestActions.fish(fishing, userId, "玩家", "bank-only-fine");
        assertBalances("0", money("200").subtract(money(fine)).toPlainString());
        assertEquals(money(fine), result.penalty());
        assertEquals(money(fine).negate(), result.netProfit());
        assertLossLedger("bank-only-fine", EconomyLedgerReason.FISHING_PENALTY, fine);
        var attempt = attempts.findById("bank-only-fine").orElseThrow();
        assertEquals(money(fine), attempt.getPenalty());
        assertTrue(attempt.getReceipt().endsWith("收益：-" + money(fine) + "金币"));
        assertEquals(4, attempt.getReceipt().lines().count());
    }

    @ParameterizedTest
    @CsvSource({
            "25,100,80,0,45", "100,50,80,20,50", "25,20,80,0,-35",
            "0,0,80,0,-80", "25,-100,80,0,-155", "0,-100,80,0,-180"
    })
    void fishingFinesUseWalletFirstAndAddTheWholeShortfallToDebt(String wallet, String bank, String loss,
                                                       String walletAfter, String bankAfter) {
        seed(wallet, bank);
        prepareFishing("exam");
        var result = FishingTestActions.fish(fishing, userId, "玩家", "limited-fine");
        assertBalances(walletAfter, bankAfter);
        assertEquals(money(loss), result.penalty());
        assertEquals(money(loss).negate(), result.netProfit());
        assertLossLedger("limited-fine", EconomyLedgerReason.FISHING_PENALTY, loss);
        assertEquals(money(loss), attempts.findById("limited-fine").orElseThrow().getPenalty());
        assertEquals(money(bankAfter).min(BigDecimal.ZERO).compareTo(money(bank).min(BigDecimal.ZERO)) < 0,
                result.outcomeText().contains("计入欠款"));
    }

    @Test
    void raffleFineCollectsBankFundsAfterTheEntryPaymentEmptiesWallet() {
        seed("1000", "100");
        prizes.deleteAll();
        var prize = new EconomyRafflePrize();
        prize.setCode("test-bank-fine");
        prize.setName("测试罚款");
        prize.setDescription("罚款50金币");
        prize.setType(EconomyPrizeType.FINE);
        prize.setWeight(1);
        prize.setStock(-1);
        prize.setMoneyAmount(money("50"));
        prize.setEnabled(true);
        prizes.saveAndFlush(prize);
        var result = raffle.draw(userId, "玩家", 1, "raffle-bank-fine");
        assertEquals(money("50"), result.prizes().getFirst().reward());
        assertBalances("0", "50");
        assertLossLedger("raffle-bank-fine:reward:1", EconomyLedgerReason.RAFFLE_FINE, "50");
    }

    @Test
    void arrestFineCollectsFromBankWithoutChangingTheftTargetRules() {
        seed("0", "100");
        when(robRandom.chance(anyDouble())).thenReturn(false, true);
        var result = rob.rob(userId, "玩家", userId + 1, "arrest-bank-fine");
        assertTrue(result.caught());
        assertTrue(result.poorTarget());
        assertEquals(money("50"), result.fine());
        assertBalances("0", "50");
        assertLossLedger("arrest-bank-fine", EconomyLedgerReason.ROB_FINE, "50");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRestoresBothAccountsAndRetryCannotCollectTwice() {
        userId = 98802L;
        var tx = new TransactionTemplate(transactionManager);
        var tickets = events.findAll().stream().collect(java.util.stream.Collectors.toMap(
                event -> event.getCode(), event -> event.getTickets()));
        tx.executeWithoutResult(status -> {
            seed("25", "100");
            profiles.getOrCreateForUpdate(userId, "玩家").setFishingRodPurchased(true);
            items.addItem(userId, "fishing-bait-white", 1);
        });
        tx.executeWithoutResult(status -> {
            events.findAll().forEach(event -> {
                event.setTickets(event.getCode().equals("exam") ? 10000 : 0);
                events.save(event);
            });
            FishingTestActions.fish(fishing, userId, "玩家", "rollback-bank-fine");
            assertBalances("0", "45");
            status.setRollbackOnly();
        });
        assertBalances("25", "100");
        assertFalse(operations.isProcessed("rollback-bank-fine"));
        assertFalse(attempts.existsById("rollback-bank-fine"));
        assertLossLedger("rollback-bank-fine", EconomyLedgerReason.FISHING_PENALTY, "0");
        assertTrue(baitStates.findById(userId).isEmpty());
        assertEquals(1, items.backpack(userId).getFirst().getQuantity());
        tx.executeWithoutResult(status -> {
            events.findAll().forEach(event -> {
                event.setTickets(event.getCode().equals("exam") ? 10000 : 0);
                events.save(event);
            });
            FishingTestActions.fish(fishing, userId, "玩家", "rollback-bank-fine");
            events.findAll().forEach(event -> {
                event.setTickets(tickets.get(event.getCode()));
                events.save(event);
            });
        });
        assertThrows(DuplicateEconomyOperationException.class,
                () -> FishingTestActions.fish(fishing, userId, "玩家", "rollback-bank-fine"));
        assertBalances("0", "45");
        assertLossLedger("rollback-bank-fine", EconomyLedgerReason.FISHING_PENALTY, "80");
        assertEquals(4, baitStates.findById(userId).orElseThrow().getWhiteUses());
    }
}
