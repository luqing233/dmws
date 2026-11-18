package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCast;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCast.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EconomyFishingCastRepository extends JpaRepository<EconomyFishingCast, String> {
    boolean existsByUserIdAndStatus(long userId, Status status);
    Optional<EconomyFishingCast> findFirstByUserIdOrderByCastAtDescOperationIdDesc(long userId);

    List<CastKey> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscOperationIdAsc(
            Status status, LocalDateTime now, Pageable limit);
    List<CastKey> findByBotIdAndStatusOrderByDueAtAscOperationIdAsc(long botId, Status status, Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EconomyFishingCast c where c.operationId = :operationId")
    Optional<EconomyFishingCast> lock(@Param("operationId") String operationId);

    interface CastKey {
        String getOperationId();
        long getUserId();
    }
}
