package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.fish.Fish;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingBaitRule;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Raw historical levels are never rewritten; the reward curve alone is bounded. */
public final class EconomyFishingRules {
    public static final int MAX_LEVEL = 100;
    private static final BigDecimal LEVEL_INCREMENT = new BigDecimal("0.02");
    private EconomyFishingRules() { }

    public static int effectiveLevel(int rawLevel) {
        return Math.max(1, Math.min(MAX_LEVEL, rawLevel));
    }

    public static boolean unlocked(Fish fish, int rodLevel) {
        return fish.getLevel() <= (long) Math.max(1, rodLevel) + 1;
    }

    /** Special catches get one ticket; ordinary fish keep difficulty-based weights. */
    public static long selectionWeight(Fish fish) {
        return Boolean.TRUE.equals(fish.getSpecial()) ? 1L : Math.max(1L, 120L - fish.getDifficulty());
    }

    public static BigDecimal rodMultiplier(int rawLevel) {
        return BigDecimal.ONE.add(LEVEL_INCREMENT.multiply(BigDecimal.valueOf(effectiveLevel(rawLevel) - 1))).multiply(new BigDecimal("0.5")).setScale(2);
    }

    public static double growth(int level) { return Math.pow((effectiveLevel(level)-1)/99.0,.8); }

    public static double successChance(Fish fish,int level,EconomyFishingBaitRule bait) {
        return chanceBreakdown(fish,level,bait).total();
    }

    public static ChanceBreakdown chanceBreakdown(Fish fish,int level,EconomyFishingBaitRule bait) {
        double original=Math.max(.25,Math.min(1,.82-fish.getDifficulty()/160.0
                -Math.max(0,fish.getLevel()-effectiveLevel(level))*.15));
        double rod=Math.min(.15,.30*growth(level)*(1-original));
        return new ChanceBreakdown(original,rod,bait.successBonus(),Math.min(.90,original+rod+bait.successBonus()));
    }

    public record ChanceBreakdown(double base,double rodBonus,double baitBonus,double total) { }

    public static double ordinaryWeight(Fish fish,int level,EconomyFishingBaitRule bait) {
        double tier=Math.pow(Math.clamp((fish.getLevel()-1)/9.0,0,1),1.5);
        return selectionWeight(fish)*(1+2*growth(level)*tier)*(fish.getLevel()>=7?bait.highFishWeight():1);
    }
    /** One normalized distribution; preserve baseline special mass independently of ordinary weights. */
    public static double[] probabilities(java.util.List<Fish> pool,int level,EconomyFishingBaitRule bait) {
        long specials=pool.stream().filter(f->Boolean.TRUE.equals(f.getSpecial())).count();
        double baseTotal=pool.stream().mapToDouble(EconomyFishingRules::selectionWeight).sum();
        double specialMass=Math.min(bait.specialCap(), specials/baseTotal*bait.specialMultiplier());
        double ordinaryTotal=pool.stream().filter(f->!Boolean.TRUE.equals(f.getSpecial())).mapToDouble(f->ordinaryWeight(f,level,bait)).sum();
        if(ordinaryTotal==0) specialMass=1;
        double[] result=new double[pool.size()];
        for(int i=0;i<pool.size();i++) {
            var f=pool.get(i);
            result[i]=Boolean.TRUE.equals(f.getSpecial())?specialMass/specials:(1-specialMass)*ordinaryWeight(f,level,bait)/ordinaryTotal;
        }
        return result;
    }

    public static BigDecimal upgradeCost(int rawLevel) {
        return BigDecimal.valueOf(Math.max(1, rawLevel)).multiply(new BigDecimal("1000.00"));
    }

    /** A fixed-size fish uses its neutral size factor; the actual measured size determines price. */
    public static BigDecimal sizeMultiplier(BigDecimal size, int minimum, int maximum) {
        int min = Math.max(1, minimum), max = Math.max(min, maximum);
        if (max == min) return BigDecimal.ONE;
        BigDecimal position = size.subtract(BigDecimal.valueOf(min)).max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(max - min)).divide(BigDecimal.valueOf(max - min), 12, RoundingMode.HALF_UP);
        return new BigDecimal("0.75").add(new BigDecimal("0.50").multiply(position));
    }

    public static BigDecimal reward(int basePrice, BigDecimal size, int min, int max, int rawLevel) {
        return BigDecimal.valueOf(basePrice).multiply(sizeMultiplier(size, min, max))
                .multiply(rodMultiplier(rawLevel)).setScale(2, RoundingMode.HALF_UP);
    }
}
