package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.enums.EconomyAccountScope;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EconomyAccountRepository extends JpaRepository<EconomyAccount, Long> {

    Optional<EconomyAccount> findByAccountKeyAndScope(String accountKey, EconomyAccountScope scope);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from EconomyAccount account where account.id = :id")
    Optional<EconomyAccount> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
              from EconomyAccount account
             where account.accountKey = :accountKey
               and account.scope = :scope
            """)
    Optional<EconomyAccount> findForUpdate(
            @Param("accountKey") String accountKey,
            @Param("scope") EconomyAccountScope scope
    );

    List<EconomyAccount> findAllByScopeOrderByBalanceDesc(EconomyAccountScope scope);
}
