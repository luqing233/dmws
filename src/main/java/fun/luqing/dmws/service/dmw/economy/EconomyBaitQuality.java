package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import java.math.BigDecimal;

public enum EconomyBaitQuality {
    WHITE("白", "fishing-bait-white", 10, 0, 1, 0),
    GREEN("绿", "fishing-bait-green", 35, .02, 1.1, .1),
    BLUE("蓝", "fishing-luck-bait", 75, .04, 1.25, .2),
    PURPLE("紫", "fishing-bait-purple", 150, .06, 1.4, .3),
    GOLD("金", "fishing-bait-gold", 250, .08, 1.6, .4),
    RED("红", "fishing-bait-red", 400, .10, 1.8, 0);

    public final String label, code;
    public final BigDecimal price;
    public final double successBonus, highFishWeight, riskReduction;
    EconomyBaitQuality(String label, String code, int price, double success, double weight, double risk) {
        this.label=label; this.code=code; this.price=BigDecimal.valueOf(price);
        this.successBonus=success; this.highFishWeight=weight; this.riskReduction=risk;
    }
    public String description() {
        return switch(this) {
            case WHITE -> "朴素实惠的基础鱼饵，适合初入鱼塘的钓鱼人。";
            case GREEN -> "加了点鱼喜欢的配方，便宜，也更顺手。";
            case BLUE -> "认真钓鱼的常备选择，效果与成本较为均衡。";
            case PURPLE -> "香味已经传到了深水区，大鱼开始留意你的鱼钩。";
            case GOLD -> "讲究稳中求进的高级配方，少些意外，多些收获。";
            case RED -> "鱼会不会来不知道，稀奇古怪的东西肯定闻着味了。\n惊喜与风险并存，不保证盈利。";
        };
    }
    public String displayName() { return "幸运鱼饵（"+label+"）"; }
    public static EconomyBaitQuality fromCode(String code) {
        for(var q:values()) if(q.code.equals(code)) return q;
        return null;
    }
    public static EconomyBaitQuality parse(String value) {
        String v=value==null?"":value.trim().replace("幸运鱼饵", "").replace("色", "")
                .replace("（", "").replace("）", "").replace("(", "").replace(")", "").trim();
        for(var q:values()) if(q.label.equals(v)||q.name().equalsIgnoreCase(v)||q.code.equals(v)) return q;
        throw new EconomyException("INVALID_BAIT", "鱼饵品质：白、绿、蓝、紫、金、红");
    }
}
