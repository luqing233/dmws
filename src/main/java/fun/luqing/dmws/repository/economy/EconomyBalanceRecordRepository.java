package fun.luqing.dmws.repository.economy;

import fun.luqing.dmws.entity.economy.EconomyBalanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EconomyBalanceRecordRepository extends JpaRepository<EconomyBalanceRecord, EconomyBalanceRecord.IdClass> {

    Optional<EconomyBalanceRecord> findByContextAndCurrencyAndUuid(String context, String currency, String uuid);

}
