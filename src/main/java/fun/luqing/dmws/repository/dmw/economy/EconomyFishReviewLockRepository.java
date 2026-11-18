package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishReviewLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface EconomyFishReviewLockRepository extends JpaRepository<EconomyFishReviewLock, Integer> {
    @Modifying
    @Query(value = "INSERT IGNORE INTO economy_fish_review_lock (id) VALUES (1)", nativeQuery = true)
    int initialize();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from EconomyFishReviewLock l where l.id=1")
    Optional<EconomyFishReviewLock> lockCatalog();
}
