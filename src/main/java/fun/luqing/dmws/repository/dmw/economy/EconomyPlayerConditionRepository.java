package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyPlayerCondition;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EconomyPlayerConditionRepository extends JpaRepository<EconomyPlayerCondition, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EconomyPlayerCondition s where s.userId = :id")
    Optional<EconomyPlayerCondition> findForUpdate(@Param("id") long id);

    @Query("select s.userId from EconomyPlayerCondition s where s.hospitalUntil <= :now or s.jailUntil <= :now order by s.userId")
    List<Long> findDue(@Param("now") LocalDateTime now, Pageable page);
}
