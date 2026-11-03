package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankDeposit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EconomyPrivateBankDepositRepository extends JpaRepository<EconomyPrivateBankDeposit, Long> {

    Optional<EconomyPrivateBankDeposit> findByBankIdAndUserId(Long bankId, Long userId);

    List<EconomyPrivateBankDeposit> findByUserIdOrderByIdAsc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select deposit
              from EconomyPrivateBankDeposit deposit
             where deposit.bankId = :bankId
               and deposit.userId = :userId
            """)
    Optional<EconomyPrivateBankDeposit> findForUpdate(
            @Param("bankId") Long bankId,
            @Param("userId") Long userId
    );
}
