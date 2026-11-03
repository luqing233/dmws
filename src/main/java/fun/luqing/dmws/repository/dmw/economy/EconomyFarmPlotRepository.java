package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFarmPlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EconomyFarmPlotRepository extends JpaRepository<EconomyFarmPlot, Long> {

    List<EconomyFarmPlot> findByUserIdOrderByPlotIndexAsc(Long userId);

    Optional<EconomyFarmPlot> findByUserIdAndPlotIndex(Long userId, int plotIndex);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select plot
              from EconomyFarmPlot plot
             where plot.userId = :userId
               and plot.plotIndex = :plotIndex
            """)
    Optional<EconomyFarmPlot> findForUpdate(
            @Param("userId") Long userId,
            @Param("plotIndex") int plotIndex
    );
}
