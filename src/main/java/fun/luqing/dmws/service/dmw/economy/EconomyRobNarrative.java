package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/** Shared QQ/MCP narration; wording randomness never changes gameplay outcomes. */
public final class EconomyRobNarrative {
    private EconomyRobNarrative() { }

    public static String format(EconomyRobService.RobResult result, String target, LocalDateTime now) {
        return format(result, target, now, ThreadLocalRandom.current().nextInt(3));
    }

    static String format(EconomyRobService.RobResult r, String target, LocalDateTime now, int variant) {
        String name = target == null || target.isBlank() ? "对方" : target.trim();
        String text;
        if (r.victimAssaulted()) text = pick(variant,
                "你翻遍了" + name + "的口袋，金币没找到，脾气倒是找到了。冲动之下把对方送进了医院。",
                name + "的口袋比你的计划还空。你恼羞成怒动了手，这回真摊上事了。",
                "这趟劫没打成，你却把" + name + "打进了医院。金币没赚到，犯罪值倒是营业了。");
        else if (r.poorTarget()) text = pick(variant,
                "你和" + name + "对视三秒，确认过口袋，是比你还穷的人。今日打劫，空手下班。",
                name + "把口袋翻得比你的脸还干净。你沉默了，贫穷完成了一次双向奔赴。",
                "你打算劫富济自己，结果" + name + "连“富”这个前置条件都没凑齐。");
        else if (r.resisted()) text = pick(variant,
                "你冲着" + name + "喊了声“打劫”，对方反手把你打成了“挂号”。",
                "你以为" + name + "是提款机，结果对方给你办理了“住院套餐”。",
                "你负责放狠话，" + name + "负责放倒你。这趟不是去取钱，是去取号。");
        else if (r.caught()) text = pick(variant,
                "钱还没摸到，警车倒是先摸到了你。恭喜解锁“包接包送”的局子体验。",
                "你刚摆好打劫的架势，警察就帮你摆好了蹲局子的姿势。",
                "你的致富计划被警笛打断了。金币没到手，银手镯先安排上了。");
        else if (r.success()) text = pick(variant,
                "你从" + name + "那里顺走了 " + EconomyMoney.format(r.stolen()) + " 金币。钱包没学会走路，你倒是跑得挺快。",
                "趁" + name + "还在加载反应，你已带着 " + EconomyMoney.format(r.stolen()) + " 金币撤退。腿脚很快，路子很歪。",
                "这回居然让你得手了！从" + name + "那里抢到 " + EconomyMoney.format(r.stolen()) + " 金币，跑路比上班积极。");
        else text = pick(variant,
                name + "一个转身就跑没影了。你追了两步，决定把这次失败归咎于鞋不合脚。",
                "你在" + name + "面前表演了半天凶狠，对方没配合，钱包也没配合。",
                "打劫计划执行到第一步就散架了。" + name + "溜了，你只能假装自己出来散步。");

        if (r.hospitalUntil() != null) {
            String patient = r.victimAssaulted() ? name : "你";
            text += "\n" + patient + "的伤势：" + EconomyGameText.injury(r.injury()) + "（" + r.injury() + "/100）"
                    + "\n最晚强制出院：" + EconomyGameText.time(r.hospitalUntil(), now)
                    + "\n本人发送“出院”可办理出院，按实际住院时间结算。";
        }
        if (r.caught()) {
            if (r.poorTarget()) text += "\n你还没走远，警察就来给这场闹剧收尾了。";
            text += "\n释放时间：" + EconomyGameText.time(r.jailUntil(), now);
            if (r.fine().signum() > 0) text += "\n罚款：" + EconomyMoney.format(r.fine()) + " 金币";
            text += "\n可请低犯罪值的群友发送“保释 @你”，先用“保释费用 @你”查价。";
        }
        if (r.electricBatonUsed()) text += "\n电棍滋了一声，耗电20，剩余 " + r.electricBatonEnergy() + "/100；装备还在，包赢不在服务范围。";
        if (r.robBoostUsed()) text += "\n本次消耗了 1 次红牛增益，增益没承诺包赢。";
        return text + "\n犯罪值：" + r.crime() + "/100";
    }

    private static String pick(int variant, String first, String second, String third) {
        return switch (Math.floorMod(variant, 3)) { case 0 -> first; case 1 -> second; default -> third; };
    }
}
