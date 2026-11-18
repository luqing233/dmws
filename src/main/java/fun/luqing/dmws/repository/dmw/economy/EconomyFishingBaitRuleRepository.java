package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingBaitRule;
import fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EconomyFishingBaitRuleRepository extends JpaRepository<EconomyFishingBaitRule, EconomyBaitQuality> { }
