package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyElectricBaton;
import fun.luqing.dmws.repository.dmw.economy.EconomyElectricBatonRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EconomyEquipmentService {
    public static final String BATON = "electric-baton";
    public static final String CHARGER = "electric-baton-charger";
    public static final int CAPACITY = 100;
    public static final int USE_COST = 20;
    private final EconomyElectricBatonRepository batons;
    private final EconomyUserItemRepository inventory;
    private final EconomyGameTime time;

    /** Caller holds the player's profile lock, after reserving the operation. */
    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public Integer useForRobbery(long userId) {
        for (var baton : synchronize(userId)) {
            int energy = currentEnergy(baton);
            if (energy >= USE_COST) {
                // Settle accrued whole minutes before unplugging; insufficient charge keeps its anchor.
                baton.setEnergy(energy - USE_COST);
                baton.setChargingSince(null);
                return baton.getEnergy();
            }
        }
        return null;
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public String startCharging(long userId) {
        if (quantity(userId, CHARGER) < 1) throw new EconomyException("CHARGER_REQUIRED", "请先购买电棍充电器");
        var owned = synchronize(userId);
        if (owned.isEmpty()) throw new EconomyException("BATON_REQUIRED", "你还没有电棍");
        owned.forEach(this::refresh);
        if (owned.stream().anyMatch(b -> b.getChargingSince() != null))
            throw new EconomyException("ALREADY_CHARGING", "已有电棍正在充电，无需重复操作；发送“电棍电量”查看进度");
        var baton = owned.stream().filter(b -> b.getEnergy() < CAPACITY)
                .min(Comparator.comparingInt(EconomyElectricBaton::getEnergy))
                .orElseThrow(() -> new EconomyException("BATON_FULL", "电棍都是满电，无需充电"));
        baton.setChargingSince(time.now());
        return "电棍已接上充电器，当前电量 " + baton.getEnergy() + "/100；每分钟恢复1点，约 "
                + (CAPACITY - baton.getEnergy()) + " 分钟充满。抢劫时满20电会自动停止充电并使用，不足20则继续充电。";
    }

    /** Read-only view never allocates equipment or resets a charging clock. */
    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public EquipmentView view(long userId) {
        int owned = quantity(userId, BATON);
        var stored = batons.findByUserIdOrderByIdAsc(userId);
        var entries = new ArrayList<BatonView>();
        for (var b : stored.stream().limit(owned).toList()) {
            int energy = currentEnergy(b);
            boolean charging = b.getChargingSince() != null && energy < CAPACITY;
            entries.add(new BatonView(energy, charging, charging ? CAPACITY - energy : 0));
        }
        for (int i = entries.size(); i < owned; i++) entries.add(new BatonView(CAPACITY, false, 0));
        return new EquipmentView(List.copyOf(entries), quantity(userId, CHARGER) > 0);
    }

    private List<EconomyElectricBaton> synchronize(long userId) {
        int count = quantity(userId, BATON);
        var rows = new ArrayList<>(batons.findByUserIdOrderByIdAsc(userId));
        // No currently supported command removes reusable equipment. Never discard state if an audit detects drift.
        if (rows.size() > count) throw new EconomyException("EQUIPMENT_STOCK_MISMATCH", "电棍库存与装备记录不一致，请联系管理员核对");
        while (rows.size() < count) {
            var b = new EconomyElectricBaton(); b.setUserId(userId);
            rows.add(batons.save(b));
        }
        return rows;
    }

    private int quantity(long userId, String code) {
        return inventory.findByUserIdAndItemCode(userId, code).map(i -> Math.max(0, i.getQuantity())).orElse(0);
    }

    private int currentEnergy(EconomyElectricBaton b) {
        if (b.getChargingSince() == null) return b.getEnergy();
        long minutes = Math.max(0, Duration.between(b.getChargingSince(), time.now()).toMinutes());
        return (int) Math.min(CAPACITY, b.getEnergy() + Math.min(CAPACITY, minutes));
    }

    private void refresh(EconomyElectricBaton b) {
        int energy = currentEnergy(b);
        // Partial charge keeps its original snapshot/anchor, avoiding rounding loss on frequent queries.
        if (energy == CAPACITY && b.getChargingSince() != null) {
            b.setEnergy(CAPACITY); b.setChargingSince(null);
        }
    }

    public record BatonView(int energy, boolean charging, int minutesToFull) { }
    public record EquipmentView(List<BatonView> batons, boolean chargerOwned) {
        public String text() {
            if (batons.isEmpty()) return "尚未拥有电棍";
            StringBuilder out = new StringBuilder("电棍电量");
            for (int i = 0; i < batons.size(); i++) {
                var b = batons.get(i);
                out.append("\n").append(batons.size() == 1 ? "" : (i + 1) + ". ")
                        .append(b.energy()).append("/100")
                        .append(b.charging() ? "（充电中，约" + b.minutesToFull() + "分钟充满；"
                                + (b.energy() >= USE_COST ? "抢劫时可拔电使用）" : "不足20电，继续充电）")
                                : b.energy() >= USE_COST ? "（可用）" : "（需充电）");
                if (i == 19 && batons.size() > 20) { out.append("\n另有 ").append(batons.size() - 20).append(" 根"); break; }
            }
            return out.append("\n每次自动耗电20；").append(chargerOwned ? "发送“使用 电棍充电器”开始充电" : "购买电棍充电器后可重复充电").toString();
        }
    }
}
