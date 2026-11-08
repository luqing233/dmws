package fun.luqing.dmws.service.dmw.economy;
import fun.luqing.dmws.common.economy.*;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.enums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
@Service @RequiredArgsConstructor
public class EconomyFishingEventService {
    private final EconomyFishingEventRepository events;
    private final EconomyCombatService combat;
    private final EconomyConditionService conditions;
    private final EconomyLedgerService ledger;
    private final EconomyFishingRandom random;
    private final EconomyGameTime time;
    @Transactional(transactionManager="dmwTransactionManager", propagation=Propagation.MANDATORY)
    public Outcome encounter(EconomyUserProfile player,EconomyBaitQuality bait,String operation) {
        var profile=combat.profile(player.getUserId());
        profile.setFishingAttempts(profile.getFishingAttempts()+1);
        boolean protectedPlayer=profile.getFishingAttempts()<=10;
        if(protectedPlayer && bait!=EconomyBaitQuality.RED) return null;
        var pool=events.findByEnabledTrueOrderByCodeAsc();
        long total=0;
        for(var e:pool) {
            if(e.getTickets()<0 || e.getPenalty().signum()<0 || !java.util.Set.of("FINE","JAIL","CROCODILE","FUN","REWARD").contains(e.getKind())
                    || e.getJailMinutes()<1 || e.getJailMinutes()>30 || e.getStrengthMin()<1 || e.getStrengthMax()<e.getStrengthMin()
                    || e.getStrengthMax()>100 || e.getRewardMin().signum()<1 || e.getRewardMax().compareTo(e.getRewardMin())<0
                    || e.getWinInjuryPercent()<0 || e.getWinInjuryPercent()>100 || e.getLightPercent()<0 || e.getMediumPercent()<0
                    || e.getLightPercent()+e.getMediumPercent()>100) throw new EconomyException("INVALID_EVENT", "钓鱼事件配置无效，请联系管理员");
            if(!java.util.Set.of("FUN","REWARD").contains(e.getKind())) total+=e.getTickets();
        }
        if(total>10000) throw new EconomyException("INVALID_EVENT", "钓鱼事件总概率超过100%，请联系管理员");
        EconomyFishingEvent selected=null;
        if(bait==EconomyBaitQuality.RED) {
            long roll=random.weight(10000);
            String category=roll<800?"FUN":roll<1400?"REWARD":roll<1800?"CROCODILE":roll<2000?"NEGATIVE":null;
            if(category==null || protectedPlayer && !java.util.Set.of("FUN","REWARD").contains(category)) return null;
            var choices=pool.stream().filter(e->category.equals("NEGATIVE")?java.util.Set.of("FINE","JAIL").contains(e.getKind()):category.equals(e.getKind())).toList();
            long tickets=choices.stream().mapToLong(EconomyFishingEvent::getTickets).sum();
            if(tickets<=0) return null;
            long choice=random.weight(tickets);
            for(var e:choices) {choice-=e.getTickets();if(choice<0) {selected=e;break;}}
        } else {
            long ticket=random.weight(10000);
            for(var e:pool) {
                if(java.util.Set.of("FUN","REWARD").contains(e.getKind())) continue;
                ticket-=Math.round(e.getTickets()*(1-bait.riskReduction));
                if(ticket<0) {selected=e;break;}
            }
        }
        if(selected==null) return null;
        var e=selected;long user=player.getUserId();
        if(java.util.Set.of("FUN","REWARD").contains(e.getKind())) {
            long spread=e.getRewardMax().subtract(e.getRewardMin()).longValueExact();
            if(spread>100000) throw new EconomyException("INVALID_EVENT","事件奖励范围过大");
            BigDecimal reward=e.getRewardMin().add(BigDecimal.valueOf(random.weight(spread+1))).setScale(2);
            ledger.creditUser(user,EconomyAccountScope.WALLET,reward,operation,EconomyLedgerReason.FISHING_EVENT_REWARD,user,e.getName());
            return new Outcome(e,true,reward,BigDecimal.ZERO,0,false,null,"发现藏着的金币，获得"+EconomyMoney.format(reward)+"金币。");
        }
        var state=conditions.lock(user,player.getNickname());
        if(e.getKind().equals("FINE")) {
            var penalty=ledger.debitUpTo(user,EconomyAccountScope.WALLET,e.getPenalty(),operation,EconomyLedgerReason.FISHING_PENALTY,user,e.getName());
            String text="本次额外损失"+EconomyMoney.format(penalty)+"金币。";
            if(penalty.compareTo(e.getPenalty())<0) text+="钱包不足，仅扣除剩余金币。";
            return new Outcome(e,false,BigDecimal.ZERO,penalty,0,false,null,text);
        }
        if(e.getKind().equals("JAIL")) {
            state.setJailUntil(time.now().plusMinutes(e.getJailMinutes()));
            return new Outcome(e,false,BigDecimal.ZERO,BigDecimal.ZERO,0,false,null,
                    "警方带你回局子协助调查"+e.getJailMinutes()+"分钟。");
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
        String text="你没能制服它。";
        if(won) {
            BigDecimal ratio=e.getStrengthMax()==e.getStrengthMin()?BigDecimal.ONE:BigDecimal.valueOf(strength-e.getStrengthMin()).divide(BigDecimal.valueOf(e.getStrengthMax()-e.getStrengthMin()),10,RoundingMode.HALF_UP);
            reward=e.getRewardMin().add(e.getRewardMax().subtract(e.getRewardMin()).multiply(ratio)).setScale(2,RoundingMode.HALF_UP);
            ledger.creditUser(user,EconomyAccountScope.WALLET,reward,operation,EconomyLedgerReason.FISHING_EVENT_REWARD,user,"制服尼罗鳄奖金");
            combat.victory(user,"crocodile");
            text="你成功制服了它，获得悬赏。";
        }
        if(injury>0) {
            conditions.admit(state,injury);
            text+="你被咬成"+EconomyGameText.injury(injury)+"入院，医疗费出院另结。";
        }
        return new Outcome(e,won,reward,BigDecimal.ZERO,injury,boost.redBullUsed(),boost.batonEnergy(),text);
    }
    public record Outcome(EconomyFishingEvent event,boolean won,BigDecimal reward,BigDecimal penalty,int injury,boolean redBull,Integer batonEnergy,String text) { }
}
