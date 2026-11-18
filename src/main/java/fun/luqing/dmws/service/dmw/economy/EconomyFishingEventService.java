package fun.luqing.dmws.service.dmw.economy;
import fun.luqing.dmws.common.economy.*;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.enums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
@Service @RequiredArgsConstructor
public class EconomyFishingEventService {
    private final EconomyFishingEventRepository events;
    private final EconomyCombatService combat;
    private final EconomyConditionService conditions;
    private final EconomyLedgerService ledger;
    private final EconomyFishingRandom random;
    private final EconomyGameTime time;
    private final EconomyFishingTextService texts;
    @Transactional(transactionManager="dmwTransactionManager", propagation=Propagation.MANDATORY)
    public Outcome encounter(EconomyUserProfile player, EconomyFishingBaitRule rule, String operation) {
        var profile=combat.profile(player.getUserId());
        profile.setFishingAttempts(profile.getFishingAttempts()+1);
        var e = selectEvent(rule);
        if (e == null) return null;
        long user = player.getUserId();
        if(e.getKind().equals("MONEY")) {
            long steps = e.getAmountMax().subtract(e.getAmountMin()).divide(e.getAmountStep()).longValueExact();
            BigDecimal amount = e.getAmountMin().add(e.getAmountStep().multiply(BigDecimal.valueOf(random.weight(steps + 1))));
            if (amount.signum() < 0) {
                var payment = ledger.chargeLoss(user, amount.negate(), operation, EconomyLedgerReason.FISHING_PENALTY, user, e.getName());
                return new Outcome(e, false, BigDecimal.ZERO, payment.fee(), payment.borrowed(), 0, false, null);
            }
            if (amount.signum() > 0)
                ledger.creditUser(user,EconomyAccountScope.WALLET,amount,operation,EconomyLedgerReason.FISHING_EVENT_REWARD,user,e.getName());
            return new Outcome(e,true,amount,BigDecimal.ZERO,BigDecimal.ZERO,0,false,null);
        }
        var state=conditions.lock(user,player.getNickname());
        if(e.getKind().equals("JAIL")) {
            state.setJailUntil(time.now().plusMinutes(e.getJailMinutes()));
            return new Outcome(e,false,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0,false,null);
        }
        int strength=e.getStrengthMin()+(int)random.weight((long)e.getStrengthMax()-e.getStrengthMin()+1);
        var boost=combat.useBoosts(player);
        boolean won=random.chance(EconomyCombatService.winChance(profile.getStrength(),strength,0.5,boost.bonus(),0.10,0.90));
        int injury=0;
        if(won) {if(random.chance(e.getWinInjuryPercent()/100.0)) injury=1+(int)random.weight(30);}
        else {
            long grade=random.weight(100);
            injury=grade<e.getLightPercent()?1+(int)random.weight(30):grade<e.getLightPercent()+e.getMediumPercent()?31+(int)random.weight(30):61+(int)random.weight(40);
        }
        BigDecimal reward=BigDecimal.ZERO;
        if(won) {
            BigDecimal ratio=e.getStrengthMax()==e.getStrengthMin()?BigDecimal.ONE:BigDecimal.valueOf(strength-e.getStrengthMin()).divide(BigDecimal.valueOf(e.getStrengthMax()-e.getStrengthMin()),10,RoundingMode.HALF_UP);
            reward=e.getAmountMin().add(e.getAmountMax().subtract(e.getAmountMin()).multiply(ratio)).setScale(2,RoundingMode.HALF_UP);
            if (reward.signum() > 0)
                ledger.creditUser(user,EconomyAccountScope.WALLET,reward,operation,EconomyLedgerReason.FISHING_EVENT_REWARD,user,e.getName());
            combat.victory(user,e.getCode());
        }
        if(injury>0) {
            conditions.admit(state,injury);
        }
        return new Outcome(e,won,reward,BigDecimal.ZERO,BigDecimal.ZERO,injury,boost.redBullUsed(),boost.batonEnergy());
    }

    private EconomyFishingEvent selectEvent(EconomyFishingBaitRule rule) {
        var available = events.findByEnabledTrueOrderByCodeAsc();
        var pools = new ArrayList<DrawPool>();
        var seen = new HashSet<String>();
        long budget = 0;
        for (var pool : rule.getPools()) {
            if (pool == null || pool.getPoolCode() == null || pool.getPoolCode().isBlank() || !seen.add(pool.getPoolCode())
                    || pool.getTicketScaleBps() < 0 || pool.getTicketScaleBps() > 10000
                    || pool.getTicketBudget() != null && pool.getTicketBudget() < 0) throw invalidEvent();
            boolean grouped = pool.getTicketBudget() != null;
            var entries = new ArrayList<WeightedEvent>();
            long total = 0;
            for (var event : available) {
                if (!pool.getPoolCode().equals(event.getPoolCode())) continue;
                if (event.getTickets() < 0) throw invalidEvent();
                long tickets = grouped ? event.getTickets()
                        : Math.round(event.getTickets() * (pool.getTicketScaleBps() / 10000.0));
                if (tickets == 0) continue;
                validate(event);
                entries.add(new WeightedEvent(event, tickets));
                total += tickets;
            }
            long span = grouped ? pool.getTicketBudget() : total;
            budget += span;
            pools.add(new DrawPool(entries, span, total, grouped));
        }
        if (budget > 10000) throw invalidEvent();
        long roll = random.weight(10000);
        for (var pool : pools) {
            if (roll >= pool.span()) {
                roll -= pool.span();
                continue;
            }
            if (pool.total() == 0) return null;
            long ticket = pool.grouped() ? random.weight(pool.total()) : roll;
            for (var entry : pool.entries()) {
                ticket -= entry.tickets();
                if (ticket < 0) return entry.event();
            }
        }
        return null;
    }

    private void validate(EconomyFishingEvent event) {
        boolean valid = switch (event.getKind()) {
            case "MONEY" -> validRange(event);
            case "JAIL" -> event.getJailMinutes() >= 1 && event.getJailMinutes() <= 30;
            case "COMBAT" -> validRange(event) && event.getAmountMin().signum() >= 0
                    && event.getStrengthMin() >= 1 && event.getStrengthMax() >= event.getStrengthMin()
                    && event.getStrengthMax() <= 100 && event.getWinInjuryPercent() >= 0
                    && event.getWinInjuryPercent() <= 100 && event.getLightPercent() >= 0
                    && event.getLightPercent() <= 100 && event.getMediumPercent() >= 0
                    && event.getMediumPercent() <= 100 - event.getLightPercent();
            default -> false;
        };
        if (!valid) throw invalidEvent();
    }

    private boolean validRange(EconomyFishingEvent event) {
        if (event.getAmountMin() == null || event.getAmountMax() == null || event.getAmountStep() == null
                || event.getAmountStep().signum() <= 0 || event.getAmountMin().compareTo(event.getAmountMax()) > 0
                || event.getAmountMin().abs().compareTo(EconomyMoney.MAX) > 0
                || event.getAmountMax().abs().compareTo(EconomyMoney.MAX) > 0) return false;
        try {
            event.getAmountMin().setScale(2, RoundingMode.UNNECESSARY);
            event.getAmountMax().setScale(2, RoundingMode.UNNECESSARY);
            event.getAmountStep().setScale(2, RoundingMode.UNNECESSARY);
            long steps = event.getAmountMax().subtract(event.getAmountMin())
                    .divide(event.getAmountStep(), 0, RoundingMode.UNNECESSARY).longValueExact();
            return steps <= 10000000;
        } catch (ArithmeticException invalid) { return false; }
    }

    private EconomyException invalidEvent() {
        return new EconomyException("INVALID_EVENT", texts.message("INVALID_EVENT", java.util.Map.of(), EconomyFishingRandom.newSeed()));
    }

    private record WeightedEvent(EconomyFishingEvent event, long tickets) { }
    private record DrawPool(List<WeightedEvent> entries, long span, long total, boolean grouped) { }
    public record Outcome(EconomyFishingEvent event,boolean won,BigDecimal reward,BigDecimal penalty,
                          BigDecimal borrowed,int injury,boolean redBull,Integer batonEnergy) { }
}
