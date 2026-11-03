package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyRaffleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EconomyRaffleRecordRepository extends JpaRepository<EconomyRaffleRecord, Long> {

    List<EconomyRaffleRecord> findByOperationIdOrderByIdAsc(String operationId);
}
