package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyItemDefinitionRepository extends JpaRepository<EconomyItemDefinition, String> {

    List<EconomyItemDefinition> findAllByEnabledTrueOrderByPriceAsc();

    Optional<EconomyItemDefinition> findFirstByEnabledTrueAndName(String name);
}
