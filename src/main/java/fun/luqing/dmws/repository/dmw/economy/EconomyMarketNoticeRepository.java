package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EconomyMarketNoticeRepository extends JpaRepository<EconomyMarketNotice, Long> {
    Optional<EconomyMarketNotice> findFirstByStatusOrderByIdAsc(String status);
}
