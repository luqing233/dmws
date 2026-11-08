package fun.luqing.dmws.service.dmw.economy;
import fun.luqing.dmws.common.economy.EconomyMoney;
public final class EconomyFishingText {
    private EconomyFishingText() { }
    private static String line(String text) {
        if(text==null) return "";
        return text.replaceAll("[\\r\\n]+"," ").replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\uFE0F]", "").trim();
    }
    public static String receipt(EconomyFishingService.FishingResult r) {
        String name=line(r.fishName()),description=line(r.fishDescription());
        if(description.isBlank()) description="暂无介绍";
        boolean event=r.outcomeText()!=null;
        String first=(!r.success()&&!event)?name+" 脱钩逃走了。":"你钓上了"+(name.equals("尸体")?"一具 ":java.util.Set.of("漂流瓶","神秘包裹","小宝箱","遗失的钱袋","悬赏物品").contains(name)?"一个 ":name.equals("钓鱼佬的留言")?"一张 ":name.equals("靴子")?"一只 ":name.equals("五年高考三年模拟")?"一本 ":"一条 ")+name+"。";
        if(event && name.equals("挂底")) first="你的鱼钩挂底了。";
        String outcome=event?line(r.outcomeText()):r.success()?"大小："+EconomyMoney.format(r.size())+"厘米，售价"+EconomyMoney.format(r.reward())+"金币。":"";
        if(!event && !r.success() && r.estimatedValueMin()!=null && r.estimatedValueMax()!=null) {
            outcome+="据情报人员估价："+EconomyMoney.format(r.estimatedValueMin());
            if(r.estimatedValueMin().compareTo(r.estimatedValueMax())!=0) outcome+="～"+EconomyMoney.format(r.estimatedValueMax());
            outcome+="金币。";
        }
        if(r.baitNotice()!=null) outcome+=" "+line(r.baitNotice());
        if(outcome.isBlank()) outcome="未获得售鱼收入。";
        return first+"\n描述："+description+"\n"+outcome+"\n收益："+(r.netProfit().signum()>0?"+":"")+EconomyMoney.format(r.netProfit())+"金币";
    }
}
