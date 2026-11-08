package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface EconomyMarketStateRepository extends JpaRepository<EconomyMarketState, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EconomyMarketState s where s.id = 1")
    Optional<EconomyMarketState> lockMarket();
}
