package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyLedgerEntry;
import fun.luqing.dmws.enums.EconomyAccountScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EconomyLedgerEntryRepository extends JpaRepository<EconomyLedgerEntry, Long> {

    List<EconomyLedgerEntry> findByAccountKeyAndScopeOrderByIdDesc(
            String accountKey,
            EconomyAccountScope scope
    );
}
