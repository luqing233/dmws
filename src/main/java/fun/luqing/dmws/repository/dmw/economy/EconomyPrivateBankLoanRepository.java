package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankLoan;
import fun.luqing.dmws.enums.EconomyLoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EconomyPrivateBankLoanRepository extends JpaRepository<EconomyPrivateBankLoan, Long> {

    List<EconomyPrivateBankLoan> findByBorrowerIdAndStatusInOrderByDueAtAsc(
            Long borrowerId,
            Collection<EconomyLoanStatus> statuses
    );

    List<EconomyPrivateBankLoan> findByStatusInAndDueAtBefore(
            Collection<EconomyLoanStatus> statuses,
            LocalDateTime dueAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from EconomyPrivateBankLoan loan where loan.id = :id")
    Optional<EconomyPrivateBankLoan> findForUpdate(@Param("id") Long id);
}
