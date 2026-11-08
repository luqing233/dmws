package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;

public interface EconomyMarketPositionRepository extends JpaRepository<EconomyMarketPosition, Long> {
    boolean existsByUserIdAndStatus(Long userId, Status status);
    List<EconomyMarketPosition> findByUserIdAndStatusOrderByIdAsc(Long userId, Status status);
    List<EconomyMarketPosition> findByStatusOrderByUserIdAscIdAsc(Status status);
    List<EconomyMarketPosition> findByUserIdAndStatusNotOrderByIdDesc(Long userId, Status status, Pageable page);
    long countByUserIdAndOpenedAtGreaterThanEqualAndOpenedAtLessThan(Long userId, LocalDateTime start, LocalDateTime end);
}
