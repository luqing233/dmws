package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EconomyOperationRepository extends JpaRepository<EconomyOperation, String> {
}
