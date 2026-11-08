package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EconomyFishingCatchRepository extends JpaRepository<EconomyFishingCatch, Long> {

    long countByUserId(Long userId);

    List<EconomyFishingCatch> findTop20ByUserIdOrderByIdDesc(Long userId);

    @org.springframework.data.jpa.repository.Query("select c from EconomyFishingCatch c where c.reward > 0 and not exists (select b.id from EconomyFishingCatch b where b.userId=c.userId and (b.reward>c.reward or (b.reward=c.reward and b.id<c.id))) order by c.reward desc,c.id asc")
    List<EconomyFishingCatch> findBestPerPlayer(org.springframework.data.domain.Pageable page);
}
