package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EconomyFishingCatchRepository extends JpaRepository<EconomyFishingCatch, Long> {

    List<EconomyFishingCatch> findTop20ByUserIdOrderByIdDesc(Long userId);

    List<EconomyFishingCatch> findTop100ByOrderBySizeCmDesc();
}
