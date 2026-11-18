package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.*;
import fun.luqing.dmws.config.EconomyMarketProperties;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Status;
import fun.luqing.dmws.enums.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static fun.luqing.dmws.enums.EconomyAccountScope.*;
import static fun.luqing.dmws.enums.EconomyLedgerReason.*;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "dmwTransactionManager", isolation = Isolation.READ_COMMITTED)
public class EconomyMarketService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal LIQUIDATION = new BigDecimal("0.80");
    private final EconomyMarketProperties rules;
    private final EconomyProperties economy;
    private final EconomyMarketStateRepository states;
    private final EconomyMarketQuoteRepository quotes;
    private final EconomyMarketPositionRepository positions;
    private final EconomyMarketReceiptRepository receipts;
    private final EconomyMarketProvisioner provisioner;
    private final EconomyMarketRandom random;
    private final EconomyGameTime time;
    private final EconomyUserProfileService profiles;
    private final EconomyConditionService conditions;
    private final EconomyOperationService operations;
    private final EconomyLedgerService ledger;
    private final EconomyLedgerQueryService ledgerQueries;
    private final EconomyMarketNoticeRepository notices;
    private final fun.luqing.dmws.config.DmwConfig config;
    private final EconomyDebtService debtService;
    private final EconomyDebtQueryService debtQueries;

    public ActionResult setTradingEnabled(long actor, boolean enabled, String operationId) {
        if (!config.isMaster(actor)) throw new EconomyException("PERMISSION_DENIED", "只有机器人主人可以开关交易功能");
        if (!economy.isEnabled()) throw new EconomyException("ECONOMY_DISABLED", "经济系统未开放");
        EconomyMarketState state = lockMarket();
        String fingerprint = fingerprint("SWITCH", actor, enabled);
        ActionResult replay = replay(operationId, actor, "SWITCH", fingerprint);
        if (replay != null) return replay;
        operations.start(operationId, "MARKET_SWITCH", actor, enabled ? "开启交易" : "关闭交易");
        state.setTradingEnabled(enabled);
        return remember(operationId, actor, "SWITCH", fingerprint, null, ZERO, ZERO,
                enabled ? "交易功能已开启" + (rules.isAcceptingOpens() ? "，行情就绪后可开仓" : "，配置仍暂停新开仓")
                        : "交易功能已关闭，禁止新开仓；行情、平仓、爆仓结算和还款继续开放");
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public boolean available() {
        return economy.isEnabled() && (rules.isEnabled() || states.existsById(1));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public String tradingStatus() {
        var state = states.findById(1).orElse(null);
        boolean enabled = state != null && state.getTradingEnabled() != null ? state.getTradingEnabled() : rules.isEnabled();
        return economy.isEnabled() && enabled && rules.isAcceptingOpens() ? "交易功能已开启" : "交易功能已关闭或暂停新开仓";
    }

    /** Global lock precedes sorted profiles/accounts. READ_COMMITTED avoids pre-lock MySQL snapshots on replay/settlement. */
    public long tick() {
        enabled();
        EconomyMarketState state = lockMarket();
        expireMarginCalls(state);
        LocalDateTime minute = quoteSlot(time.now());
        EconomyMarketQuote previous = state.getSequence() == 0 ? null : quotes.findById(state.getSequence()).orElseThrow();
        if (previous != null && !minute.isAfter(previous.getQuotedAt())) return state.getSequence();
        long sequence = state.getSequence() + 1;
        operations.start("market-tick:" + sequence, "MARKET_TICK", null, "统一模拟行情");
        EconomyMarketQuote quote = new EconomyMarketQuote();
        quote.setId(sequence);
        quote.setQuotedAt(minute);
        for (EconomyFxPair pair : EconomyFxPair.values()) {
            BigDecimal value = pair.initial();
            if (previous != null) {
                BigDecimal change = random.nextReturn(state.getEntropyKey(), sequence, pair);
                if (change == null || change.abs().compareTo(new BigDecimal("0.03")) > 0)
                    throw new IllegalStateException("Invalid simulated market return");
                value = previous.price(pair).multiply(BigDecimal.ONE.add(change)).setScale(8, RoundingMode.HALF_UP);
            }
            if (value.signum() <= 0 || value.precision() - value.scale() > 16)
                throw new IllegalStateException("Simulated price out of range; market paused");
            quote.price(pair, value);
        }
        quotes.saveAndFlush(quote);
        state.setSequence(sequence);
        List<EconomyMarketPosition> active = positions.findByStatusOrderByUserIdAscIdAsc(Status.OPEN);
        active.stream().map(EconomyMarketPosition::getUserId).distinct()
                .forEach(id -> profiles.getOrCreateForUpdate(id, ""));
        for (EconomyMarketPosition position : active) {
            assessRisk(position, quote);
        }
        return sequence;
    }

    public ActionResult open(long userId, String nickname, EconomyFxPair pair, Side side,
                             BigDecimal margin, int leverage, String operationId) {
        return openInGroup(userId, nickname, pair, side, margin, leverage, operationId, null);
    }

    public ActionResult openInGroup(long userId, String nickname, EconomyFxPair pair, Side side,
                                   BigDecimal margin, int leverage, String operationId, Long groupId) {
        enabled();
        EconomyMarketState state = lockMarket();
        String fingerprint = fingerprint("OPEN", userId, pair, side, margin, leverage);
        ActionResult replay = replay(operationId, userId, "OPEN", fingerprint);
        if (replay != null) return replay;
        operations.start(operationId, "MARKET_OPEN", userId, "模拟外汇开仓");
        profiles.getOrCreateForUpdate(userId, nickname);
        conditions.requireAvailable(userId, nickname);
        EconomyMarketQuote quote = freshQuote(state);
        if (pair == null || side == null) throw new EconomyException("INVALID_ORDER", "请选择货币对和做多/做空方向");
        margin = exactMoney(margin);
        if (leverage < 1 || leverage > 100) throw new EconomyException("INVALID_LEVERAGE", "杠杆必须为1到100的整数");
        boolean tradingEnabled = state.getTradingEnabled() == null ? rules.isEnabled() : state.getTradingEnabled();
        if (!tradingEnabled || !rules.isAcceptingOpens() || state.getNetIssued().compareTo(rules.getNetIssuanceBudget()) >= 0)
            throw new EconomyException("MARKET_OPEN_PAUSED", "当前暂停新开仓，已有仓位仍可平仓");
        if (debtQueries.total(userId).signum() > 0)
            throw new EconomyException("MARKET_DEBT_OUTSTANDING", "请先还清欠款，再开新仓");
        if (margin.compareTo(rules.getMinimumMargin()) < 0 || margin.compareTo(rules.getPositionMarginLimit()) > 0)
            throw new EconomyException("INVALID_MARGIN", "单笔保证金范围：" + money(rules.getMinimumMargin()) + "～" + money(rules.getPositionMarginLimit()));
        List<EconomyMarketPosition> active = positions.findByUserIdAndStatusOrderByIdAsc(userId, Status.OPEN);
        if (active.stream().anyMatch(p -> p.getMarginCallQuote() != null))
            throw new EconomyException("MARKET_MARGIN_CALL_PENDING", "请先处理待补保证金的仓位，再开新仓");
        BigDecimal used = active.stream().map(EconomyMarketPosition::getMargin).reduce(ZERO, BigDecimal::add);
        if (active.size() >= rules.getMaxPositions() || used.add(margin).compareTo(rules.getTotalMarginLimit()) > 0)
            throw new EconomyException("MARKET_POSITION_LIMIT", "持仓数量或保证金总额达到上限");
        LocalDate today = time.now().toLocalDate();
        if (positions.countByUserIdAndOpenedAtGreaterThanEqualAndOpenedAtLessThan(userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay()) >= rules.getDailyOpenLimit())
            throw new EconomyException("MARKET_DAILY_LIMIT", "今天的开仓次数已用完");
        BigDecimal fee = openingFee(margin, leverage);
        ledger.debitUserWithBankFallback(userId, margin, operationId, MARKET_MARGIN, userId, "开仓冻结保证金");
        ledger.creditAccount(escrowKey(userId), PLUGIN, userId, margin, operationId, MARKET_MARGIN, userId, "冻结交易保证金（不计银行利息）");
        ledger.debitUserWithBankFallback(userId, fee, operationId, MARKET_FEE, userId, "外汇开仓手续费");
        state.setFees(state.getFees().add(fee));
        state.setNetIssued(state.getNetIssued().subtract(fee));
        EconomyMarketPosition position = new EconomyMarketPosition();
        position.setOperationId(operationId); position.setUserId(userId); position.setPair(pair); position.setSide(side);
        position.setMargin(margin); position.setLeverage(leverage); position.setNotional(margin.multiply(BigDecimal.valueOf(leverage)));
        position.setOpeningFee(fee); position.setEntryPrice(quote.price(pair)); position.setEntryQuote(quote.getId()); position.setOpenedAt(time.now());
        position.setOriginGroupId(groupId != null && groupId > 0 ? groupId : null);
        positions.saveAndFlush(position);
        String text = "开仓成功 #" + position.getId() + "｜" + pair.code() + " " + direction(side) + " " + leverage + "倍"
                + "\n保证金 " + money(margin) + "，手续费 " + money(fee) + " 金币"
                + "\n开仓价 " + price(position.getEntryPrice()) + "（模拟行情）"
                + "\n名义仓位 " + money(position.getNotional()) + "；亏损60%提醒、80%进入5分钟补仓期，逾期按触发价强平";
        return remember(operationId, userId, "OPEN", fingerprint, position.getId(), margin.add(fee), ZERO, text);
    }

    public ActionResult close(long userId, String nickname, long positionId, String operationId) {
        enabled();
        EconomyMarketState state = lockMarket();
        String fingerprint = fingerprint("CLOSE", userId, positionId);
        ActionResult replay = replay(operationId, userId, "CLOSE", fingerprint);
        if (replay != null) return replay;
        operations.start(operationId, "MARKET_CLOSE", userId, "模拟外汇平仓");
        profiles.getOrCreateForUpdate(userId, nickname);
        EconomyMarketPosition position = positions.findById(positionId)
                .filter(p -> p.getUserId() == userId)
                .orElseThrow(() -> new EconomyException("POSITION_NOT_FOUND", "找不到你的这个仓位"));
        if (position.getStatus() != Status.OPEN) {
            return remember(operationId, userId, "CLOSE", fingerprint, positionId, ZERO, debtQueries.total(userId),
                    "该仓位已结算，本次未重复收付。\n" + position.getCloseReceipt());
        }
        EconomyMarketQuote quote = position.getMarginCallQuote() == null ? freshQuote(state) : callQuote(position);
        settle(state, position, quote, operationId, liquidated(position, quote.price(position.getPair())));
        return remember(operationId, userId, "CLOSE", fingerprint, positionId, position.getPayout(), debtQueries.total(userId), position.getCloseReceipt());
    }

    /** Add collateral only: nominal exposure and entry price never change. No new loan is created. */
    public ActionResult addMargin(long userId, String nickname, long positionId, BigDecimal amount, String operationId) {
        enabled();
        EconomyMarketState state = lockMarket();
        String fingerprint = fingerprint("MARGIN", userId, positionId, amount);
        ActionResult replay = replay(operationId, userId, "MARGIN", fingerprint);
        if (replay != null) return replay;
        operations.start(operationId, "MARKET_ADD_MARGIN", userId, "追加交易保证金");
        profiles.getOrCreateForUpdate(userId, nickname);
        EconomyMarketPosition position = positions.findById(positionId).filter(p -> p.getUserId() == userId)
                .orElseThrow(() -> new EconomyException("POSITION_NOT_FOUND", "找不到你的这个仓位"));
        if (position.getStatus() != Status.OPEN)
            throw new EconomyException("POSITION_CLOSED", "该仓位已结算，不能再补保证金");
        if (position.getMarginCallDeadline() != null && !time.now().isBefore(position.getMarginCallDeadline())) {
            forceLiquidate(state, position);
            return remember(operationId, userId, "MARGIN", fingerprint, positionId, ZERO, debtQueries.total(userId),
                    "补仓期限已到，本次未扣补仓资金。\n" + position.getCloseReceipt());
        }
        amount = exactMoney(amount);
        BigDecimal total = positions.findByUserIdAndStatusOrderByIdAsc(userId, Status.OPEN).stream()
                .map(EconomyMarketPosition::getMargin).reduce(ZERO, BigDecimal::add);
        if (position.getMargin().add(amount).compareTo(rules.getPositionMarginLimit()) > 0
                || total.add(amount).compareTo(rules.getTotalMarginLimit()) > 0)
            throw new EconomyException("MARKET_POSITION_LIMIT", "追加后将超过单仓或总保证金上限，本次未扣款；请及时平仓");
        EconomyMarketQuote quote = position.getMarginCallQuote() == null ? freshQuote(state) : callQuote(position);
        ledger.debitUserWithBankFallback(userId, amount, operationId, MARKET_MARGIN, userId, "追加保证金");
        ledger.creditAccount(escrowKey(userId), PLUGIN, userId, amount, operationId, MARKET_MARGIN, userId, "追加冻结保证金");
        position.setMargin(position.getMargin().add(amount));
        if (liquidated(position, quote.price(position.getPair()))) {
            // An inadequate top-up does not buy another response window.
            settle(state, position, quote, operationId, true);
            queueNotice(position, "补仓后仍达到80%爆仓线，已按触发价强平。\n" + position.getCloseReceipt());
            return remember(operationId, userId, "MARGIN", fingerprint, positionId, amount, debtQueries.total(userId),
                    "补入 " + money(amount) + " 金币后仍不足维持仓位。\n" + position.getCloseReceipt());
        }
        position.setMarginCallQuote(null); position.setMarginCallDeadline(null);
        if (lossRatio(position, quote).compareTo(new BigDecimal("0.55")) < 0) position.setRiskWarned(false);
        return remember(operationId, userId, "MARGIN", fingerprint, positionId, amount, debtQueries.total(userId),
                "已追加保证金 " + money(amount) + " 金币至 #" + positionId + "，总保证金 " + money(position.getMargin())
                        + "\n名义仓位和开仓价不变，当前补仓期限已解除；后续仍可能亏损、穿仓");
    }

    private void expireMarginCalls(EconomyMarketState state) {
        List<EconomyMarketPosition> active = positions.findByStatusOrderByUserIdAscIdAsc(Status.OPEN);
        active.stream().map(EconomyMarketPosition::getUserId).distinct().forEach(id -> profiles.getOrCreateForUpdate(id, ""));
        for (var position : active) {
            if (position.getMarginCallDeadline() != null && !time.now().isBefore(position.getMarginCallDeadline()))
                forceLiquidate(state, position);
        }
    }

    private void forceLiquidate(EconomyMarketState state, EconomyMarketPosition position) {
        String op = "market-liquidate:" + position.getId();
        operations.start(op, "MARKET_LIQUIDATE", position.getUserId(), "补仓超时强制平仓");
        settle(state, position, callQuote(position), op, true);
        queueNotice(position, "补仓期限已到，已强制平仓。\n" + position.getCloseReceipt());
    }

    private EconomyMarketQuote callQuote(EconomyMarketPosition position) {
        return quotes.findById(position.getMarginCallQuote()).orElseThrow();
    }

    private BigDecimal lossRatio(EconomyMarketPosition position, EconomyMarketQuote quote) {
        return pnl(position, quote.price(position.getPair())).negate().max(ZERO).divide(position.getMargin(), 8, RoundingMode.HALF_UP);
    }

    public static BigDecimal suggestedMargin(EconomyMarketPosition position, BigDecimal profit) {
        return profit.negate().max(ZERO).divide(new BigDecimal("0.55"), 2, RoundingMode.CEILING)
                .subtract(position.getMargin()).max(ZERO);
    }

    private void assessRisk(EconomyMarketPosition position, EconomyMarketQuote quote) {
        if (position.getMarginCallQuote() != null) return;
        BigDecimal loss = pnl(position, quote.price(position.getPair())).negate().max(ZERO);
        if (loss.compareTo(position.getMargin().multiply(new BigDecimal("0.55"))) < 0) position.setRiskWarned(false);
        if (liquidated(position, quote.price(position.getPair()))) {
            position.setMarginCallQuote(quote.getId());
            // End before another public quote can offer a free decision on subsequent prices.
            LocalDateTime deadline = time.now().plusMinutes(5);
            LocalDateTime nextQuote = quote.getQuotedAt().plusMinutes(10);
            position.setMarginCallDeadline(deadline.isBefore(nextQuote) ? deadline : nextQuote);
            position.setRiskWarned(true);
            queueNotice(position, "仓位 #" + position.getId() + " " + position.getPair().code()
                    + " 已达到80%爆仓线，是否补保证金？\n请在 " + noticeTime(position.getMarginCallDeadline())
                    + " 前发送：交易补仓 " + position.getId() + " <金额>，或 交易平仓 " + position.getId()
                    + "\n不补、超时或补后仍达80%将按触发价 " + price(quote.price(position.getPair())) + " 强平。"
                    + "\n建议补入 " + money(suggestedMargin(position, loss.negate())) + " 金币（仍受保证金上限限制）");
        } else if (loss.compareTo(position.getMargin().multiply(new BigDecimal("0.60"))) >= 0 && !position.isRiskWarned()) {
            position.setRiskWarned(true);
            queueNotice(position, "仓位 #" + position.getId() + " " + position.getPair().code()
                    + " 已亏损保证金的60%以上，请关注风险。\n可发送：交易补仓 " + position.getId() + " <金额>，或 交易平仓 " + position.getId()
                    + "\n建议补入 " + money(suggestedMargin(position, loss.negate())) + " 金币；达80%后最多5分钟，不处理将强平。");
        }
    }

    private static String noticeTime(LocalDateTime value) {
        return value.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));
    }

    private void queueNotice(EconomyMarketPosition position, String message) {
        if (position.getOriginGroupId() == null) return;
        var notice = new EconomyMarketNotice(); notice.setUserId(position.getUserId()); notice.setGroupId(position.getOriginGroupId());
        notice.setKind(position.getStatus() != Status.OPEN ? "SETTLED" : position.getMarginCallQuote() != null ? "CALL" : "WARNING");
        notice.setTriggerQuote(position.getMarginCallQuote());
        notice.setPositionId(position.getId()); notice.setMessage("行情提醒 " + noticeTime(time.now()) + "\n" + message); notices.save(notice);
    }

    /** Reserve once before contacting OneBot. Uncertain sends stay SENDING across restarts. */
    public Notice claimNotice() {
        lockMarket();
        for (int count = 0; count < 50; count++) {
            var row = notices.findFirstByStatusOrderByIdAsc("PENDING").orElse(null);
            if (row == null) return null;
            var position = positions.findById(row.getPositionId()).orElseThrow();
            boolean obsolete = !row.getKind().equals("SETTLED") && (position.getStatus() != Status.OPEN
                    || (row.getKind().equals("CALL") && !Objects.equals(row.getTriggerQuote(), position.getMarginCallQuote()))
                    || (row.getKind().equals("WARNING") && (!position.isRiskWarned() || position.getMarginCallQuote() != null)));
            if (obsolete) {
                row.setStatus("OBSOLETE"); continue;
            }
            row.setStatus("SENDING");
            return new Notice(row.getId(), row.getUserId(), row.getGroupId(), row.getMessage());
        }
        return null;
    }

    public void finishNotice(long id, String status, String messageId) {
        if (!Set.of("SENT", "FAILED", "UNKNOWN").contains(status)) throw new IllegalArgumentException("Invalid notice status");
        lockMarket();
        var row = notices.findById(id).orElseThrow();
        if (!row.getStatus().equals("SENDING")) return;
        row.setStatus(status); row.setMessageId(messageId);
    }

    public ActionResult repay(long userId, String nickname, BigDecimal amount, String operationId) {
        lockMarket();
        String fingerprint = fingerprint("REPAY", userId, amount);
        ActionResult replay = replay(operationId, userId, "REPAY", fingerprint);
        if (replay != null) return replay;
        var result = debtService.repay(userId, nickname, amount == null ? null : exactMoney(amount), operationId);
        return remember(operationId, userId, "REPAY", fingerprint, null, result.paid(), result.remaining(), result.message());
    }

    private void settle(EconomyMarketState state, EconomyMarketPosition p, EconomyMarketQuote quote, String op, boolean forced) {
        long user = p.getUserId();
        BigDecimal pnl = pnl(p, quote.price(p.getPair()));
        BigDecimal returned = p.getMargin().add(pnl);
        BigDecimal newDebt = ZERO, repaid = ZERO, payout = ZERO, extraPaid = ZERO;
        ledger.debitAccount(escrowKey(user), PLUGIN, user, p.getMargin(), op, MARKET_SETTLEMENT, user, "解除仓位保证金");
        if (returned.signum() < 0) {
            BigDecimal shortfall = returned.negate();
            BigDecimal walletPaid = ledger.debitUpTo(user, WALLET, shortfall, op, MARKET_SETTLEMENT, user, "穿仓损失扣钱包");
            BigDecimal bankNeed = shortfall.subtract(walletPaid);
            BigDecimal bankPaid = bankNeed.signum() > 0 ? ledger.debitUpTo(user, MAIN_BANK, bankNeed, op, MARKET_SETTLEMENT, user, "穿仓损失扣主银行正余额") : ZERO;
            extraPaid = walletPaid.add(bankPaid);
            newDebt = shortfall.subtract(extraPaid);
            if (newDebt.signum() > 0) ledger.creditAccount(debtKey(user), PLUGIN, user, newDebt, op, MARKET_DEBT, user, "穿仓新增交易负债（正数为应还款）");
        } else if (returned.signum() > 0) {
            repaid = returned.min(debt(user));
            if (repaid.signum() > 0) ledger.debitAccount(debtKey(user), PLUGIN, user, repaid, op, MARKET_REPAY, user, "平仓回款优先抵扣交易欠款");
            payout = returned.subtract(repaid);
            if (payout.signum() > 0) ledger.creditUser(user, WALLET, payout, op, MARKET_SETTLEMENT, user, "平仓实际回款");
        }
        state.setNetIssued(state.getNetIssued().add(pnl).add(newDebt).subtract(repaid));
        p.setStatus(forced ? Status.LIQUIDATED : Status.CLOSED); p.setExitPrice(quote.price(p.getPair()));
        p.setExitQuote(quote.getId()); p.setClosedAt(time.now()); p.setPnl(pnl); p.setNewDebt(newDebt);
        p.setDebtRepaid(repaid); p.setPayout(payout); p.setExtraPaid(extraPaid);
        String text = (forced ? "爆仓结算" : "平仓成功") + " #" + p.getId() + "｜" + p.getPair().code() + " " + direction(p.getSide())
                + "\n成交价 " + price(p.getExitPrice()) + "，本笔净收益 " + signed(pnl.subtract(p.getOpeningFee())) + " 金币（已扣开仓费）"
                + "\n返还钱包 " + money(payout) + " 金币";
        if (extraPaid.signum() > 0) text += "，另扣余额 " + money(extraPaid);
        if (repaid.signum() > 0) text += "\n已抵扣欠款 " + money(repaid);
        if (newDebt.signum() > 0) text += "\n新增欠款 " + money(newDebt) + " 金币，请还清后再开仓";
        p.setCloseReceipt(text);
        p.setMarginCallDeadline(null); p.setMarginCallQuote(null);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager", isolation = Isolation.REPEATABLE_READ)
    public MarketView market() {
        enabled();
        var rows = quotes.findAllByOrderByIdDesc(PageRequest.of(0, 2));
        if (rows.isEmpty()) return new MarketView(null, null, false, List.of());
        var latest = rows.getFirst();
        var previous = rows.size() > 1 ? rows.get(1) : latest;
        List<QuoteView> values = Arrays.stream(EconomyFxPair.values()).map(pair -> new QuoteView(pair.code(), pair.label(),
                latest.price(pair), latest.price(pair).divide(previous.price(pair), 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE))).toList();
        return new MarketView(latest.getId(), latest.getQuotedAt(), fresh(latest), values);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager", isolation = Isolation.REPEATABLE_READ)
    public Portfolio portfolio(long userId) {
        enabled();
        var latest = quotes.findAllByOrderByIdDesc(PageRequest.of(0, 1));
        EconomyMarketQuote quote = latest.isEmpty() ? null : latest.getFirst();
        List<PositionView> views = positions.findByUserIdAndStatusOrderByIdAsc(userId, Status.OPEN).stream().map(p -> view(p, quote)).toList();
        BigDecimal margin = views.stream().map(PositionView::margin).reduce(ZERO, BigDecimal::add);
        BigDecimal floating = views.stream().map(PositionView::pnl).reduce(ZERO, BigDecimal::add);
        BigDecimal owed = debtQueries.total(userId);
        return new Portfolio(quote == null ? null : quote.getQuotedAt(), quote != null && fresh(quote), margin, floating,
                owed, margin.add(floating).subtract(owed), views);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager", isolation = Isolation.REPEATABLE_READ)
    public List<PositionView> history(long userId) {
        enabled();
        return positions.findByUserIdAndStatusNotOrderByIdDesc(userId, Status.OPEN, PageRequest.of(0, 20)).stream().map(p -> view(p, null)).toList();
    }

    private PositionView view(EconomyMarketPosition p, EconomyMarketQuote quote) {
        if (p.getStatus() == Status.OPEN && p.getMarginCallQuote() != null) quote = callQuote(p);
        BigDecimal current = p.getStatus() == Status.OPEN ? (quote == null ? p.getEntryPrice() : quote.price(p.getPair())) : p.getExitPrice();
        BigDecimal profit = p.getStatus() == Status.OPEN ? pnl(p, current) : p.getPnl();
        return new PositionView(p.getId(), p.getPair().code(), p.getSide(), p.getStatus(), p.getMargin(), p.getLeverage(), p.getNotional(),
                p.getOpeningFee(), p.getEntryPrice(), current, profit, profit.subtract(p.getOpeningFee()), p.getOpenedAt(), p.getClosedAt(), p.getCloseReceipt(), p.getMarginCallDeadline());
    }

    public static BigDecimal openingFee(BigDecimal margin, int leverage) {
        return margin.multiply(BigDecimal.valueOf(leverage)).multiply(new BigDecimal("0.00005")
                .add(new BigDecimal("0.000005").multiply(BigDecimal.valueOf(leverage)))).setScale(2, RoundingMode.HALF_UP).max(new BigDecimal("0.01"));
    }
    public static BigDecimal pnl(EconomyMarketPosition p, BigDecimal price) {
        return price.subtract(p.getEntryPrice()).multiply(p.getNotional())
                .multiply(BigDecimal.valueOf(p.getSide() == Side.LONG ? 1 : -1)).divide(p.getEntryPrice(), 2, RoundingMode.HALF_UP);
    }
    private boolean liquidated(EconomyMarketPosition p, BigDecimal price) {
        return pnl(p, price).negate().compareTo(p.getMargin().multiply(LIQUIDATION)) >= 0;
    }
    private EconomyMarketState lockMarket() {
        if (!states.existsById(1)) {
            try { provisioner.ensure(); } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException ignored) { }
        }
        return states.lockMarket().orElseThrow(() -> new IllegalStateException("Market state unavailable"));
    }
    private EconomyMarketQuote freshQuote(EconomyMarketState state) {
        var quote = quotes.findById(state.getSequence()).orElseThrow(() -> new EconomyException("MARKET_NOT_READY", "模拟行情尚未就绪，请稍后再试"));
        if (!fresh(quote)) throw new EconomyException("MARKET_STALE", "模拟行情正在更新或已暂停，本次未成交，请稍后重试");
        return quote;
    }
    public static LocalDateTime quoteSlot(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.HOURS).plusMinutes(now.getMinute() / 10 * 10L);
    }
    private boolean fresh(EconomyMarketQuote quote) { return quote.getQuotedAt().equals(quoteSlot(time.now())); }
    private void enabled() { if (!available()) throw new EconomyException("MARKET_DISABLED", "模拟外汇交易尚未开放"); }
    private BigDecimal debt(long user) { return ledgerQueries.accountBalance(debtKey(user), PLUGIN); }
    public static String debtKey(long user) { return "market:debt:" + user; }
    public static String escrowKey(long user) { return "market:margin:" + user; }
    private static BigDecimal exactMoney(BigDecimal value) {
        if (value == null || value.stripTrailingZeros().scale() > 2) throw new EconomyException("INVALID_AMOUNT", "金额最多两位小数");
        return EconomyMoney.positive(value);
    }
    private static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) canonical.append(part instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : String.valueOf(part)).append('\n');
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ActionResult replay(String op, long user, String action, String fingerprint) {
        var saved = receipts.findById(op).orElse(null);
        if (saved == null) return null;
        if (saved.getUserId() != user || !saved.getAction().equals(action) || !fingerprint.equals(saved.getRequestFingerprint()))
            throw new EconomyException("OPERATION_CONFLICT", "同一操作编号的交易参数已改变，未重复执行");
        return receipt(saved);
    }
    private ActionResult remember(String op, long user, String action, String fingerprint, Long position, BigDecimal amount, BigDecimal debt, String text) {
        var saved = new EconomyMarketReceipt(); saved.setOperationId(op); saved.setUserId(user); saved.setAction(action);
        saved.setRequestFingerprint(fingerprint);
        saved.setPositionId(position); saved.setAmount(amount); saved.setDebt(debt); saved.setDisplayText(text);
        receipts.save(saved);
        return receipt(saved);
    }
    private ActionResult receipt(EconomyMarketReceipt r) { return new ActionResult(r.getPositionId(), r.getAmount(), r.getDebt(), r.getDisplayText()); }
    public static String direction(Side side) { return side == Side.LONG ? "做多" : "做空"; }
    public static String money(BigDecimal value) { return EconomyMoney.format(value); }
    public static String signed(BigDecimal value) { return (value.signum() >= 0 ? "+" : "") + money(value); }
    public static String price(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    public record ActionResult(Long positionId, BigDecimal amount, BigDecimal debt, String displayText) { }
    public record Notice(long id, long userId, long groupId, String message) { }
    public record QuoteView(String pair, String name, BigDecimal price, BigDecimal change) { }
    public record MarketView(Long sequence, LocalDateTime quotedAt, boolean fresh, List<QuoteView> quotes) { }
    public record PositionView(long id, String pair, Side side, Status status, BigDecimal margin, int leverage, BigDecimal notional,
                               BigDecimal fee, BigDecimal entry, BigDecimal current, BigDecimal pnl, BigDecimal netProfit,
                               LocalDateTime openedAt, LocalDateTime closedAt, String closeReceipt, LocalDateTime marginCallDeadline) { }
    public record Portfolio(LocalDateTime quotedAt, boolean fresh, BigDecimal margin, BigDecimal floatingPnl,
                            BigDecimal debt, BigDecimal tradingEquity, List<PositionView> positions) { }
}
