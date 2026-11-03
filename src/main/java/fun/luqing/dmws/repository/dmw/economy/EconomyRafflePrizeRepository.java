package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EconomyRafflePrizeRepository extends JpaRepository<EconomyRafflePrize, Long> {

    Optional<EconomyRafflePrize> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select prize from EconomyRafflePrize prize where prize.enabled = true order by prize.id")
    List<EconomyRafflePrize> findAllEnabledForUpdate();
}
