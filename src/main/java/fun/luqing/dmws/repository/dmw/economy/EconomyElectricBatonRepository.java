package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyElectricBaton;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EconomyElectricBatonRepository extends JpaRepository<EconomyElectricBaton, Long> {
    List<EconomyElectricBaton> findByUserIdOrderByIdAsc(Long userId);
}
