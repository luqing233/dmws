package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyBalanceRecord;
import fun.luqing.dmws.enums.AccountContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EconomyBalanceRecordRepository extends JpaRepository<EconomyBalanceRecord, Long> {

    Optional<EconomyBalanceRecord> findByContextAndUserId(AccountContext context, Long userId);
}
