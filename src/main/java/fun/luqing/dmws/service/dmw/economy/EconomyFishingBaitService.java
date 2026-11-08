package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.enums.EconomyLedgerReason;

@Service @RequiredArgsConstructor
public class EconomyFishingBaitService {
    private final EconomyFishingBaitStateRepository states;
    private final EconomyUserItemRepository inventory;
    private final EconomyUserProfileService profiles;
    private final EconomyOperationService operations;
    private final EconomyItemDefinitionRepository definitions;
    private final EconomyLedgerService ledger;

    // All writers hold the player profile lock before accessing this state or inventory.
    private EconomyFishingBaitState state(EconomyUserProfile p) {
        var s=states.findById(p.getUserId()).orElseGet(()-> {
            var created=new EconomyFishingBaitState();created.setUserId(p.getUserId());return states.save(created);
        });
        if(p.getFishingLuckCharges()>0) {
            s.remaining(EconomyBaitQuality.BLUE, Math.addExact(s.remaining(EconomyBaitQuality.BLUE),p.getFishingLuckCharges()));
            p.setFishingLuckCharges(0);
        }
        return s;
    }
    @Transactional(transactionManager="dmwTransactionManager")
    public String select(long user,String nickname,String quality,String operation) {
        var q=EconomyBaitQuality.parse(quality);
        operations.start(operation,"FISHING_BAIT_SELECT",user,"设置鱼饵");
        var s=state(profiles.getOrCreateForUpdate(user,nickname));s.setSelected(q);
        return "默认鱼饵已设为"+q.displayName()+"，不足时自动购买1包。";
    }
    @Transactional(transactionManager="dmwTransactionManager",propagation=Propagation.MANDATORY)
    public String open(EconomyUserProfile player,EconomyBaitQuality quality,int quantity) {
        var s=state(player);
        s.remaining(quality,Math.addExact(s.remaining(quality),Math.multiplyExact((long)quantity,5L)));
        s.setSelected(quality);
        return "已启用"+quality.displayName()+quantity+"包，可用"+s.remaining(quality)+"次，并设为默认鱼饵。";
    }
    @Transactional(transactionManager="dmwTransactionManager",propagation=Propagation.MANDATORY)
    public Use consume(EconomyUserProfile player,String requested,String operation) {
        var s=state(player);
        var q=requested==null?s.getSelected():EconomyBaitQuality.parse(requested);
        var stock=inventory.findForUpdate(player.getUserId(),q.code).orElse(null);
        BigDecimal purchaseCost=EconomyMoney.zero();
        boolean defaultQuality=q==s.getSelected();
        if(s.remaining(q)==0) {
            if(stock!=null && stock.getQuantity()>0) {
                stock.setQuantity(stock.getQuantity()-1);stock.setUpdatedAt(LocalDateTime.now());
            } else {
                // A one-off different quality must not trigger a purchase of expensive bait.
                if(!defaultQuality) throw new EconomyException("BAIT_REQUIRED",q.displayName()+"不足，请购买或切换默认鱼饵。");
                var definition=definitions.findById(q.code)
                        .filter(d->Boolean.TRUE.equals(d.getEnabled()) && d.getEffectType()==EconomyItemEffectType.FISHING_LUCK)
                        .orElseThrow(()->new EconomyException("ITEM_NOT_FOUND",q.displayName()+"已下架，无法自动购买。"));
                purchaseCost=EconomyMoney.positive(definition.getPrice());
                ledger.debitUserWithBankFallback(player.getUserId(),purchaseCost,operation,
                        EconomyLedgerReason.SHOP_PURCHASE,player.getUserId(),"自动购买"+q.displayName()+"1包");
            }
            // A purchased package is opened immediately in this same fishing transaction.
            s.remaining(q,5);
        }
        s.remaining(q,s.remaining(q)-1);
        return new Use(q,s.remaining(q)==0&&(stock==null||stock.getQuantity()==0),purchaseCost,defaultQuality);
    }
    @Transactional(readOnly=true,transactionManager="dmwTransactionManager")
    public String describe(long user) {
        var s=states.findById(user).orElse(null);
        var p=profiles.get(user);
        StringBuilder text=new StringBuilder("当前鱼饵：").append((s==null?EconomyBaitQuality.WHITE:s.getSelected()).displayName());
        for(var q:EconomyBaitQuality.values()) {
            int packs=inventory.findByUserIdAndItemCode(user,q.code).map(EconomyUserItem::getQuantity).orElse(0);
            long uses=(s==null?0:s.remaining(q))+(q==EconomyBaitQuality.BLUE?p.getFishingLuckCharges():0);
            text.append("\n").append(q.displayName()).append("：").append(packs).append("包，已启用余").append(uses).append("次");
        }
        return text.append("\n每包5次；默认品质不足时自动购买1包，优先钱包、主银行正余额补足。收益含本次自动购买支出，不含此前购入成本。\n设置鱼饵 蓝；钓鱼 红（仅本次）").toString();
    }
    public record Use(EconomyBaitQuality quality,boolean exhausted,BigDecimal purchaseCost,boolean defaultQuality) { }
}
