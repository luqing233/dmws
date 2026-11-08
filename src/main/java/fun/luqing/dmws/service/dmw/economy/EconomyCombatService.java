package fun.luqing.dmws.service.dmw.economy;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
public class EconomyCombatService {
    private final EconomyCombatProfileRepository profiles;
    private final EconomyCombatVictoryRepository victories;
    private final EconomyEquipmentService equipment;
    private final EconomyGameTime time;
    private final EconomyFishingCatchRepository catches;
    /** The caller reserves its operation and holds the player's profile lock. */
    @Transactional(transactionManager="dmwTransactionManager", propagation=Propagation.MANDATORY)
    public EconomyCombatProfile profile(long user) {
        return profiles.findById(user).orElseGet(() -> {
            var p=new EconomyCombatProfile();p.setUserId(user);p.setFishingAttempts(Math.min(10,catches.countByUserId(user)));return profiles.save(p);
        });
    }
    @Transactional(transactionManager="dmwTransactionManager", readOnly=true)
    public int strength(long user) { return profiles.findById(user).map(EconomyCombatProfile::getStrength).orElse(50); }
    @Transactional(transactionManager="dmwTransactionManager", propagation=Propagation.MANDATORY)
    public Boost useBoosts(EconomyUserProfile profile) {
        Integer energy=equipment.useForRobbery(profile.getUserId());
        boolean red=profile.getRobBoostCharges()>0;
        if(red) profile.setRobBoostCharges(profile.getRobBoostCharges()-1);
        return new Boost(red,energy);
    }
    @Transactional(transactionManager="dmwTransactionManager", propagation=Propagation.MANDATORY)
    public void victory(long user,String opponent) {
        var p=profile(user);
        if(!time.today().equals(p.getGrowthOn())) {p.setGrowthOn(time.today());p.setGrowthToday(0);}
        String key=user+":"+time.today()+":"+opponent;
        if(p.getGrowthToday()>=2 || p.getStrength()>=100 || victories.existsById(key)) return;
        var record=new EconomyCombatVictory();record.setId(key);victories.save(record);
        p.setGrowthToday(p.getGrowthToday()+1);p.setStrength(p.getStrength()+1);
    }
    public static double winChance(int player,int opponent,double base,double bonus,double minimum,double maximum) {
        return Math.max(minimum,Math.min(maximum,base+(player-opponent)*0.005+bonus));
    }
    public static double policeChance(double base,int crime) { return Math.min(0.75,Math.max(0,base)+Math.max(0,crime)*0.0025); }
    public record Boost(boolean redBullUsed,Integer batonEnergy) {
        public double bonus() { return (redBullUsed?0.20:0)+(batonEnergy!=null?0.20:0); }
        public String text() { return (redBullUsed?" 消耗1次红牛增益。":"")+(batonEnergy!=null?" 电棍耗电20。":""); }
    }
}
