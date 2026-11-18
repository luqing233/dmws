package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyAdventureProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyPlayerCondition;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyPlayerConditionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EconomyConditionService {
    private final EconomyPlayerConditionRepository repository;
    private final EconomyUserProfileService profiles;
    private final EconomyOperationService operations;
    private final EconomyLedgerService ledger;
    private final EconomyAdventureProperties rules;
    private final EconomyGameTime time;
    private final EconomyDebtQueryService debts;

    /** Profile lock serializes creation and all condition updates. Lock pairs in user-ID order. */
    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public EconomyPlayerCondition lock(long userId, String nickname) {
        profiles.getOrCreateForUpdate(userId, nickname);
        EconomyPlayerCondition state = repository.findForUpdate(userId).orElseGet(() -> {
            var created = new EconomyPlayerCondition();
            created.setUserId(userId);
            return repository.saveAndFlush(created);
        });
        refresh(state);
        return state;
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void requireAvailable(long userId, String nickname) {
        requireAvailable(lock(userId, nickname));
    }

    public void requireAvailable(EconomyPlayerCondition state) {
        if (state.getHospitalUntil() != null) throw new EconomyException("IN_HOSPITAL", "正在住院，发送“出院”可自行结算；最晚强制出院：" + EconomyGameText.time(state.getHospitalUntil(), time.now()));
        if (state.getJailUntil() != null) throw new EconomyException("IN_JAIL", "正在服刑，释放时间：" + EconomyGameText.time(state.getJailUntil(), time.now()) + "；可请低犯罪值玩家保释");
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public List<EconomyPlayerCondition> lockPair(long firstId, String firstName, long secondId, String secondName) {
        long low = Math.min(firstId, secondId), high = Math.max(firstId, secondId);
        String lowName = firstId == low ? firstName : secondName;
        String highName = firstId == high ? firstName : secondName;
        // Acquire BOTH profile locks before refreshing overdue bills/account locks.
        profiles.getOrCreateForUpdate(low, lowName);
        profiles.getOrCreateForUpdate(high, highName);
        var lowState = lock(low, lowName);
        var highState = lock(high, highName);
        return firstId == low ? List.of(lowState, highState) : List.of(highState, lowState);
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void addCrime(EconomyPlayerCondition state, int points) {
        if (state.getCrime() == 0 && points > 0) state.setCrimeDecayAt(time.now());
        state.setCrime(Math.min(100, state.getCrime() + Math.max(0, points)));
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public String reduceCrime(long userId, String nickname, int quantity) {
        if (quantity != 1) throw new EconomyException("INVALID_QUANTITY", "金盆一次使用一个即可清零犯罪值");
        var state = lock(userId, nickname);
        if (state.getCrime() == 0) throw new EconomyException("NO_CRIME", "你的犯罪值已经为0，无需金盆洗手；道具未消耗");
        int used = time.today().equals(state.getCrimeReliefOn()) ? state.getCrimeReliefUsed() : 0;
        if (quantity > 2 - used) throw new EconomyException("CRIME_RELIEF_LIMIT", "金盆每天最多使用2个，今天还可使用 " + (2 - used) + " 个");
        int reduction = state.getCrime();
        state.setCrime(0);
        state.setCrimeDecayAt(null);
        state.setCrimeReliefOn(time.today()); state.setCrimeReliefUsed(used + quantity);
        return "金盆洗手成功，犯罪值降低 " + reduction + " 点，当前 " + state.getCrime() + "/100。"
                + (state.getJailUntil() != null ? "服刑时间不变。" : "");
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void admit(EconomyPlayerCondition state, int injury) {
        requireAvailable(state);
        int severity = Math.max(1, Math.min(100, injury));
        state.setInjury(severity);
        LocalDateTime now = time.now();
        state.setHospitalSince(now);
        state.setHospitalUntil(now.plusHours(24));
        state.setHospitalSession(UUID.randomUUID().toString());
        state.setDiagnosisFee(EconomyMoney.normalize(rules.getDiagnosisPerInjury().multiply(BigDecimal.valueOf(severity))));
        state.setHourlyFee(EconomyMoney.normalize(severity <= 30 ? rules.getLightHourlyFee()
                : severity <= 60 ? rules.getMediumHourlyFee() : rules.getSevereHourlyFee()));
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void incarcerate(EconomyPlayerCondition state) {
        int minutes = Math.min(30, 10 + (state.getCrime() / 20) * 5);
        state.setJailUntil(time.now().plusMinutes(minutes));
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Status status(long userId, String nickname) { return view(lock(userId, nickname)); }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Location location(long userId, String nickname) {
        if (userId <= 0) throw new EconomyException("INVALID_USER", "玩家 ID 必须为正整数");
        Status status = status(userId, nickname);
        String code = status.hospitalUntil() != null ? "HOSPITAL" : status.jailUntil() != null ? "JAIL" : "FREE";
        String name = status.hospitalUntil() != null ? (status.jailUntil() != null ? "医院（服刑中）" : "医院")
                : status.jailUntil() != null ? "监狱" : "自由活动";
        return new Location(userId, code, name, status.hospitalUntil(), status.jailUntil());
    }

    public record Location(long userId, String location, String locationName, LocalDateTime hospitalUntil, LocalDateTime jailUntil) { }

    public String describeLocation(Location location, String playerName) {
        String text = (playerName == null || playerName.isBlank() ? "当前位置：" : playerName + " 当前在：") + location.locationName();
        if (location.hospitalUntil() != null) text += "\n最晚强制出院：" + EconomyGameText.time(location.hospitalUntil(), time.now())
                + "\n本人发送“出院”可办理出院，按实际时间结算。";
        if (location.jailUntil() != null) text += "\n释放时间：" + EconomyGameText.time(location.jailUntil(), time.now());
        return text;
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void settleUser(long userId) { lock(userId, ""); }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Status discharge(long userId, String nickname, String operationId) {
        return dischargeReceipt(userId, nickname, operationId).status();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public DischargeReceipt dischargeReceipt(long userId, String nickname, String operationId) {
        var state = lock(userId, nickname);
        if (state.getHospitalUntil() == null) {
            if (state.getLastDischargedAt() != null) return receipt(state, false);
            throw new EconomyException("NOT_HOSPITALIZED", "当前没有住院记录");
        }
        operations.start(operationId, "HOSPITAL_DISCHARGE", userId, "主动出院");
        finishHospital(state, time.now());
        return receipt(state, true);
    }

    private DischargeReceipt receipt(EconomyPlayerCondition state, boolean dischargedNow) {
        BigDecimal debt = debts.total(state.getUserId());
        return new DischargeReceipt(view(state), dischargedNow, EconomyMoney.normalize(debt));
    }

    public record DischargeReceipt(Status status, boolean dischargedNow, BigDecimal medicalDebt) {
        public String message() {
            String text = (dischargedNow ? "出院成功\n已缴纳医药费：" : "你已出院\n医药费已结算：")
                    + EconomyMoney.format(status.lastHospitalFee()) + " 金币";
            if (medicalDebt.signum() > 0) text += "\n总欠款：" + EconomyMoney.format(medicalDebt) + " 金币（还款 <金额或全部>）";
            return text;
        }
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public BailResult bail(long helperId, String helperName, long prisonerId, String operationId) {
        if (helperId == prisonerId) throw new EconomyException("BAIL_SELF", "不能保释自己，请找低犯罪值的其他玩家");
        if (helperId <= 0 || prisonerId <= 0) throw new EconomyException("INVALID_USER", "玩家 ID 必须为正整数");
        // Serialize simultaneous bail attempts and opposite-direction interactions.
        var pair = lockPair(helperId, helperName, prisonerId, "");
        var helper = pair.get(0);
        var prisoner = pair.get(1);
        requireAvailable(helper);
        if (helper.getCrime() > rules.getBailMaxCrime()) throw new EconomyException("BAIL_BAD_REPUTATION", "犯罪值不高于 " + rules.getBailMaxCrime() + " 才能保释他人");
        if (prisoner.getJailUntil() == null) throw new EconomyException("NOT_IN_JAIL", "对方当前不在监狱，无需保释");
        operations.start(operationId, "BAIL", helperId, "保释 " + prisonerId);
        BigDecimal fee = bailFee(prisoner);
        var payment = ledger.debitUserWithBankFallback(helperId, fee, operationId,
                EconomyLedgerReason.BAIL_PAYMENT, helperId, "保释玩家 " + prisonerId);
        prisoner.setJailUntil(null);
        return new BailResult(helperId, prisonerId, fee, prisoner.getCrime(), payment.charge().balance(), payment.bankBalance());
    }

    private void refresh(EconomyPlayerCondition state) {
        LocalDateTime now = time.now();
        if (state.getCrime() == 0) state.setCrimeDecayAt(null);
        else if (state.getCrimeDecayAt() == null) state.setCrimeDecayAt(now);
        else {
            long hours = Math.max(0, Duration.between(state.getCrimeDecayAt(), now).toHours());
            state.setCrime((int) Math.max(0, state.getCrime() - Math.min(100L, hours * 5)));
            state.setCrimeDecayAt(state.getCrime() == 0 ? null : state.getCrimeDecayAt().plusHours(hours));
        }
        if (state.getHospitalUntil() != null && !now.isBefore(state.getHospitalUntil())) finishHospital(state, state.getHospitalUntil());
        if (state.getJailUntil() != null && !now.isBefore(state.getJailUntil())) state.setJailUntil(null);
    }

    private void finishHospital(EconomyPlayerCondition state, LocalDateTime end) {
        BigDecimal fee = hospitalFee(state, end);
        String operationId = "medical:" + state.getHospitalSession();
        operations.start(operationId, "HOSPITAL_FEE", state.getUserId(), "出院医疗结算");
        var payment = ledger.chargeHospital(state.getUserId(), fee, operationId,
                "住院结算，伤势 " + state.getInjury() + "，不足自动记入主银行医疗欠款");
        state.setLastHospitalFee(payment.fee());
        state.setLastMedicalLoan(payment.borrowed());
        state.setLastDischargedAt(end);
        state.setHospitalUntil(null);
        state.setHospitalSince(null);
        state.setHospitalSession(null);
        state.setInjury(0);
    }

    private BigDecimal hospitalFee(EconomyPlayerCondition state, LocalDateTime now) {
        if (state.getHospitalSince() == null) return EconomyMoney.zero();
        LocalDateTime cap = state.getHospitalUntil().isBefore(state.getHospitalSince().plusHours(24))
                ? state.getHospitalUntil() : state.getHospitalSince().plusHours(24);
        LocalDateTime end = now.isAfter(cap) ? cap : now;
        Duration elapsed = Duration.between(state.getHospitalSince(), end);
        long minutes = Math.max(0, elapsed.toMinutes() + (elapsed.minusMinutes(elapsed.toMinutes()).isZero() ? 0 : 1));
        return state.getDiagnosisFee().add(state.getHourlyFee().multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal bailFee(EconomyPlayerCondition state) {
        return EconomyMoney.normalize(rules.getBailBase().add(rules.getBailPerCrime().multiply(BigDecimal.valueOf(state.getCrime()))));
    }

    private Status view(EconomyPlayerCondition state) {
        return new Status(state.getUserId(), state.getCrime(), state.getInjury(), state.getHospitalUntil(), state.getJailUntil(),
                hospitalFee(state, time.now()), state.getJailUntil() == null ? EconomyMoney.zero() : bailFee(state),
                state.getLastHospitalFee(), state.getLastMedicalLoan(), state.getLastDischargedAt(), debts.total(state.getUserId()));
    }

    public record Status(long userId, int crime, int injury, LocalDateTime hospitalUntil, LocalDateTime jailUntil,
                         BigDecimal currentHospitalFee, BigDecimal bailFee, BigDecimal lastHospitalFee,
                         BigDecimal lastMedicalLoan, LocalDateTime lastDischargedAt, BigDecimal totalDebt) {
        public Status(long userId, int crime, int injury, LocalDateTime hospitalUntil, LocalDateTime jailUntil,
                      BigDecimal currentHospitalFee, BigDecimal bailFee, BigDecimal lastHospitalFee,
                      BigDecimal lastMedicalLoan, LocalDateTime lastDischargedAt) {
            this(userId, crime, injury, hospitalUntil, jailUntil, currentHospitalFee, bailFee,
                    lastHospitalFee, lastMedicalLoan, lastDischargedAt, EconomyMoney.zero());
        }
    }
    public record BailResult(long helperId, long prisonerId, BigDecimal fee, int prisonerCrime,
                             BigDecimal walletBalance, BigDecimal bankBalance) { }
}
