package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.enums.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyDebtTests {
    @Autowired EconomyDebtQueryService queries;
    @Autowired EconomyDebtService debts;
    @Autowired EconomyBankingService banking;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyOperationService operations;
    @Autowired EconomyPrivateBankLoanService loanService;
    @Autowired EconomyPrivateBankRepository banks;
    @Autowired EconomyPrivateBankLoanRepository loans;
    @Autowired EconomyMarketStateRepository marketStates;
    @Autowired EconomyUserProfileRepository profileRows;
    @Autowired EconomyAccountRepository accounts;
    @Autowired @Qualifier("dmwTransactionManager") PlatformTransactionManager transactionManager;

    private BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }

    private EconomyPrivateBankLoan loan(long user) {
        profiles.getOrCreateForUpdate(user, "借款人");
        var bank = new EconomyPrivateBank();
        bank.setCode("debt" + user); bank.setName("债权银行" + user); bank.setOwnerId(user + 100);
        bank.setCapitalAccountKey("bank:debt" + user + ":capital");
        banks.saveAndFlush(bank);
        var loan = new EconomyPrivateBankLoan();
        loan.setBankId(bank.getId()); loan.setBorrowerId(user); loan.setPrincipal(money("100"));
        loan.setTotalDue(money("110")); loan.setRemainingDue(money("110"));
        loan.setStatus(EconomyLoanStatus.OVERDUE); loan.setDueAt(LocalDateTime.now().minusDays(1));
        return loans.saveAndFlush(loan);
    }

    private EconomyPrivateBankLoan mixedDebt(long user) {
        var loan = loan(user);
        String op = "mixed-debt-" + user;
        operations.start(op, "TEST", user, "历史欠款");
        ledger.chargeHospital(user, money("60"), op, "住院费用");
        ledger.chargeLoss(user, money("80"), op, EconomyLedgerReason.FISHING_PENALTY, user, "钓鱼损失");
        ledger.creditAccount(EconomyMarketService.debtKey(user), EconomyAccountScope.PLUGIN, user,
                money("30"), op, EconomyLedgerReason.MARKET_DEBT, user, "历史亏损");
        return loan;
    }

    private void fund(long user, String amount) {
        String op = "debt-fund-" + user;
        operations.start(op, "TEST", user, "还款资金");
        ledger.creditUser(user, EconomyAccountScope.WALLET, money(amount), op,
                EconomyLedgerReason.ADMIN_ADJUST, user, "还款资金");
    }

    private BigDecimal capital(long user) {
        return ledger.accountBalance("bank:debt" + user + ":capital", EconomyAccountScope.PLUGIN);
    }

    @Test void oneTotalIncludesAllFeesAndLoanPrincipalAndInterestWithoutRewritingHistory() {
        long user = 98901;
        var loan = mixedDebt(user);
        assertEquals(money("280"), queries.total(user));
        assertEquals(money("280"), banking.balances(user).totalDebt());
        assertEquals(money("-140"), banking.balances(user).bank());
        assertEquals(money("100"), loan.getPrincipal());
        assertEquals(money("110"), loan.getRemainingDue());
        String text = banking.balances(user).format();
        assertTrue(text.contains("总欠款：280.00"));
        assertTrue(text.contains("银行: 0.00"));
        assertFalse(text.contains("医疗欠款")); assertFalse(text.contains("交易欠款"));
    }

    @Test void partialAndFullRepaymentReturnMoneyToTheCreditorAndOnlyCollectOutstandingDebt() {
        long user = 98902;
        var loan = mixedDebt(user); fund(user, "300");
        var first = debts.repay(user, "借款人", money("150"), "mixed-partial");
        assertEquals(money("150"), first.paid()); assertEquals(money("130"), first.remaining());
        assertEquals(money("0"), banking.balances(user).bank());
        assertEquals(money("150"), banking.balances(user).wallet());
        var second = debts.repay(user, "借款人", money("999"), "mixed-full");
        assertEquals(money("130"), second.paid()); assertEquals(money("0"), second.remaining());
        assertEquals(money("20"), banking.balances(user).wallet());
        assertEquals(money("110"), capital(user));
        assertEquals(EconomyLoanStatus.REPAID, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(money("0"), ledger.accountBalance(EconomyMarketService.debtKey(user), EconomyAccountScope.PLUGIN));
    }

    @Test void bankDepositPaysTotalDebtBeforeSavingOnlyTheExcess() {
        long user = 98903;
        mixedDebt(user); fund(user, "350");
        var result = banking.deposit(user, "借款人", money("350"), "mixed-deposit");
        assertEquals(money("0"), result.totalDebt()); assertEquals(money("70"), result.bank());
        assertEquals(money("0"), result.wallet()); assertEquals(money("110"), capital(user));
    }

    @Test void repaymentOnlyChangesMarketIssuanceForItsOriginalUnpaidMarketLoss() {
        long user = 98906;
        mixedDebt(user); fund(user, "300");
        var state = marketStates.findById(1).orElseGet(EconomyMarketState::new);
        state.setEntropyKey("0".repeat(64)); state.setNetIssued(money("100"));
        marketStates.saveAndFlush(state);
        debts.repay(user, "借款人", null, "mixed-accounting");
        assertEquals(money("70"), marketStates.findById(1).orElseThrow().getNetIssued());
        assertEquals(money("0"), queries.total(user));
    }

    @Test void queriesDoNotCreatePlayersOrAccounts() {
        long profilesBefore = profileRows.count(), accountsBefore = accounts.count();
        assertEquals(money("0"), queries.total(98909));
        queries.describe(98909);
        assertEquals(profilesBefore, profileRows.count()); assertEquals(accountsBefore, accounts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insufficientAllRepaymentRollsBackAndDuplicateRetryCannotCollectAgain() {
        long user = 98904;
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> { mixedDebt(user); fund(user, "100"); });
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(EconomyException.class,
                () -> debts.repay(user, "借款人", null, "mixed-insufficient")).getCode());
        assertEquals(money("280"), queries.total(user));
        assertEquals(money("100"), banking.balances(user).wallet());
        assertFalse(operations.isProcessed("mixed-insufficient"));
        debts.repay(user, "借款人", money("100"), "mixed-retry");
        assertThrows(DuplicateEconomyOperationException.class,
                () -> debts.repay(user, "借款人", money("100"), "mixed-retry"));
        assertEquals(money("180"), queries.total(user));
        assertEquals(money("0"), banking.balances(user).wallet());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repaymentRefreshesLoanEntitiesLoadedBeforeAnotherPaymentCommitted() {
        long user = 98907;
        var tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        long loanId = tx.execute(status -> { var loan = loan(user); fund(user, "220"); return loan.getId(); });
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            assertEquals(money("110"), loans.findById(loanId).orElseThrow().getRemainingDue());
            independent.executeWithoutResult(other -> loanService.repay(user, "借款人", "debt" + user,
                    money("55"), "earlier-contract-payment"));
            var receipt = debts.repay(user, "借款人", money("55"), "refresh-unified-payment");
            assertEquals(money("0"), receipt.remaining());
        });
        assertEquals(money("110"), capital(user));
        assertEquals(money("110"), banking.balances(user).wallet());
        assertEquals(money("0"), queries.total(user));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUnifiedAndOverdueRepaymentsCannotPayTheSameLoanTwice() throws Exception {
        long user = 98905;
        var tx = new TransactionTemplate(transactionManager);
        long loanId = tx.execute(status -> { var loan = loan(user); fund(user, "220"); return loan.getId(); });
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> {
                start.await();
                try { debts.repay(user, "借款人", null, "concurrent-debt"); }
                catch (EconomyException exception) { assertEquals("NO_DEBT", exception.getCode()); }
                return null;
            });
            var automatic = pool.submit(() -> { start.await(); loanService.settleOverdueLoan(loanId); return null; });
            start.countDown(); manual.get(20, TimeUnit.SECONDS); automatic.get(20, TimeUnit.SECONDS);
        }
        assertEquals(money("0"), queries.total(user));
        assertEquals(money("110"), capital(user));
        assertEquals(money("110"), banking.balances(user).wallet());
        assertEquals(EconomyLoanStatus.REPAID, loans.findById(loanId).orElseThrow().getStatus());
    }
}
